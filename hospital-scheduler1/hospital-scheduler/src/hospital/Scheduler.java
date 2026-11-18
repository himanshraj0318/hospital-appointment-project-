package hospital;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * Concurrent scheduler. Every doctor has a worker thread serving a PriorityBlockingQueue, but a
 * doctor only serves patients while on duty according to the simulated clock.
 *
 *  - Routine requests pick a doctor of the chosen department whose shift covers the requested time.
 *  - Emergencies go to the least-loaded member of the emergency team (PRIORITY_ER_TEAM), or to an
 *    on-duty doctor of the right department (FIFO / PRIORITY).
 *  - When a shift ends, the doctor hands the remaining queue to an on-duty colleague.
 */
public class Scheduler {
    private final List<Doctor> doctors;
    private final SchedulingMode mode;
    private final SlotBook slotBook;
    private final Repository<Appointment> repo;
    private final SimClock clock;
    private final Metrics metrics = new Metrics();

    private final List<PriorityBlockingQueue<Appointment>> queues = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger[] busy;
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private volatile boolean running = true;

    public Scheduler(List<Doctor> doctors, SchedulingMode mode, SlotBook slotBook,
                     Repository<Appointment> repo, SimClock clock) {
        this.doctors = List.copyOf(doctors);
        this.mode = mode;
        this.slotBook = slotBook;
        this.repo = repo;
        this.clock = clock;
        this.busy = new AtomicInteger[doctors.size()];

        Comparator<Appointment> order = mode.usesPriority()
                ? Comparator.comparingInt((Appointment a) -> a.getPriority().rank())
                        .thenComparingLong(Appointment::getSeq)
                : Comparator.comparingLong(Appointment::getSeq);

        for (int i = 0; i < doctors.size(); i++) {
            busy[i] = new AtomicInteger();
            queues.add(new PriorityBlockingQueue<>(64, order));
            final int idx = i;
            Thread t = new Thread(() -> work(idx), "doctor-" + doctors.get(i).getName());
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    /** Called from any number of client threads. Returns the booked appointment, or null if it could not be placed. */
    public Appointment submit(Appointment a) {
        a.setArrivalNs(System.nanoTime());
        int idx = a.getPriority() == Priority.EMERGENCY ? pickEmergencyDoctor(a.getDepartment()) : bookRoutine(a);
        if (idx < 0) {
            rejected.incrementAndGet();
            return null;
        }
        a.setDoctorId(doctors.get(idx).getId());
        a.setSeq(seqGen.getAndIncrement());
        submitted.incrementAndGet();
        repo.save(a);
        queues.get(idx).add(a);
        return a;
    }

    private int pickEmergencyDoctor(Department dept) {
        double now = clock.minuteOfDay();
        if (mode.usesEmergencyTeam()) {
            return leastLoaded(i -> doctors.get(i).isEmergencyTeam() && doctors.get(i).onDuty(now));
        }
        int idx = leastLoaded(i -> isRegularOnDuty(i, now) && doctors.get(i).getDepartment() == dept);
        return idx >= 0 ? idx : leastLoaded(i -> isRegularOnDuty(i, now));
    }

    /** Chooses a doctor in the department whose shift covers the requested slot, then books the slot. */
    private int bookRoutine(Appointment a) {
        List<Integer> candidates = IntStream.range(0, doctors.size()).boxed()
                .filter(i -> !doctors.get(i).isEmergencyTeam() && doctors.get(i).getDepartment() == a.getDepartment())
                .sorted(Comparator.comparingInt((Integer i) -> doctors.get(i).coversSlot(a.getSlot()) ? 0 : 1)
                        .thenComparingInt(this::load))
                .toList();
        for (int i : candidates) {
            int slot = slotBook.book(i, a.getSlot());
            if (slot >= 0) {
                a.setSlot(slot);
                return i;
            }
        }
        return -1;
    }

    private void work(int idx) {
        Doctor me = doctors.get(idx);
        PriorityBlockingQueue<Appointment> q = queues.get(idx);
        while (running) {
            try {
                if (!me.onDuty(clock.minuteOfDay())) {   // shift over: pass patients on, then rest
                    handOver(idx);
                    Thread.sleep(25);
                    continue;
                }
                Appointment a = q.poll(50, TimeUnit.MILLISECONDS);
                if (a == null) continue;
                busy[idx].set(1);
                a.setStartNs(System.nanoTime());
                a.setStatus(Appointment.Status.IN_PROGRESS);
                Thread.sleep(a.getServiceMs());           // simulated consultation
                a.setEndNs(System.nanoTime());
                a.setStatus(Appointment.Status.DONE);
                busy[idx].set(0);
                try { repo.update(a); } catch (RuntimeException e) { System.err.println("DB update failed: " + e.getMessage()); }
                metrics.record(a);
                completed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Moves everything still queued for an off-duty doctor to an on-duty colleague (if any). */
    private void handOver(int idx) {
        PriorityBlockingQueue<Appointment> q = queues.get(idx);
        if (q.isEmpty()) return;
        double now = clock.minuteOfDay();
        List<Appointment> drained = new ArrayList<>();
        q.drainTo(drained);
        for (Appointment a : drained) {
            int target = a.getPriority() == Priority.EMERGENCY
                    ? pickEmergencyDoctor(a.getDepartment())
                    : leastLoaded(i -> i != idx && isRegularOnDuty(i, now) && doctors.get(i).getDepartment() == a.getDepartment());
            if (target < 0) target = idx;                // nobody covering: wait for own next shift
            a.setDoctorId(doctors.get(target).getId());
            queues.get(target).add(a);
        }
    }

    private boolean isRegularOnDuty(int i, double now) {
        return !doctors.get(i).isEmergencyTeam() && doctors.get(i).onDuty(now);
    }

    private int load(int i) { return queues.get(i).size() + busy[i].get(); }

    private int leastLoaded(IntPredicate eligible) {
        int best = -1, bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < queues.size(); i++) {
            if (!eligible.test(i)) continue;
            int l = load(i);
            if (l < bestLoad) { best = i; bestLoad = l; }
        }
        return best;
    }

    /** Waits until every accepted appointment has been served, or the timeout elapses. */
    public boolean awaitDrain(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (completed.get() < submitted.get()) {
            if (System.currentTimeMillis() > deadline) return false;
            Thread.sleep(5);
        }
        return true;
    }

    public void shutdown() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    public int queueLength(int doctorIdx) { return queues.get(doctorIdx).size(); }
    public boolean isBusy(int doctorIdx) { return busy[doctorIdx].get() == 1; }
    public Metrics getMetrics() { return metrics; }
    public SlotBook getSlotBook() { return slotBook; }
    public SimClock getClock() { return clock; }
    public int getRejected() { return rejected.get(); }
    public int getSubmitted() { return submitted.get(); }
    public int getCompleted() { return completed.get(); }
    public List<Doctor> getDoctors() { return doctors; }
    public SchedulingMode getMode() { return mode; }
}

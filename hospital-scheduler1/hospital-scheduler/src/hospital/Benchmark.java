package hospital;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.function.ToDoubleFunction;

/**
 * Experiment harness. The simulated clock is frozen at a chosen hour so each run is repeatable and
 * only the doctors on duty at that hour take part. Many client threads submit requests at once.
 * Load scales with staffing (60 patients per regular doctor on duty) so hours are comparable.
 */
public class Benchmark {

    public record Result(String mode, String booking, double emergencyRatio, int hour,
                         double emergencyAvgWait, double emergencyP95Wait, double routineAvgWait,
                         double throughput, double conflicts, double doubleBookings, double rejected) {}

    static final int CLIENT_THREADS = 8;
    static final int PATIENTS_PER_DOCTOR = 60;
    static final int HOT_SLOTS = 40;   // preferred slots concentrate just after "now", creating contention

    public static Result runOnce(SchedulingMode mode, boolean safeBooking, double emergencyRatio, int hour, long seed)
            throws InterruptedException {
        List<Doctor> doctors = HospitalStaff.build();
        SimClock clock = new SimClock(hour * 60.0, 0);               // frozen clock
        SlotBook book = new SlotBook(doctors, safeBooking);
        Scheduler s = new Scheduler(doctors, mode, book, new InMemoryRepository(), clock);

        List<Department> open = HospitalStaff.openDepartments(doctors, clock.minuteOfDay());
        int patients = (int) HospitalStaff.regularOnDuty(doctors, clock.minuteOfDay()) * PATIENTS_PER_DOCTOR;
        int perClient = patients / CLIENT_THREADS;
        int nowSlot = clock.currentSlot();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CLIENT_THREADS);
        for (int c = 0; c < CLIENT_THREADS; c++) {
            final int cid = c;
            Thread t = new Thread(() -> {
                Random rnd = new Random(seed * 31 + cid);
                try {
                    start.await();
                    for (int i = 0; i < perClient; i++) {
                        boolean emergency = rnd.nextDouble() < emergencyRatio;
                        Patient p = new Patient(cid * 1000 + i, "P" + cid + "_" + i, 18 + rnd.nextInt(60));
                        Department dep = open.get(rnd.nextInt(open.size()));
                        int service = emergency ? 6 + rnd.nextInt(5) : 4 + rnd.nextInt(5);
                        int preferred = (nowSlot + rnd.nextInt(HOT_SLOTS)) % SimClock.SLOTS_PER_DAY;
                        s.submit(new Appointment(p, dep, preferred,
                                emergency ? Priority.EMERGENCY : Priority.ROUTINE, service));
                        Thread.sleep(Duration.ofNanos(200_000L + rnd.nextInt(1_500_000))); // 0.2-1.7 ms apart
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        done.await();
        if (!s.awaitDrain(60_000)) System.err.println("Warning: run did not drain (mode=" + mode + ", hour=" + hour + ")");
        s.shutdown();

        Metrics m = s.getMetrics();
        return new Result(mode.name(), safeBooking ? "SAFE" : "UNSAFE", emergencyRatio, hour,
                m.avgWaitMs(Priority.EMERGENCY), m.p95WaitMs(Priority.EMERGENCY), m.avgWaitMs(Priority.ROUTINE),
                m.throughputPerSec(), book.getConflicts(), book.getDoubleBookings(), s.getRejected());
    }

    /** Averages several trials of the same configuration. */
    public static Result average(SchedulingMode mode, boolean safe, double ratio, int hour, int trials)
            throws InterruptedException {
        List<Result> rs = new ArrayList<>();
        for (int t = 0; t < trials; t++) rs.add(runOnce(mode, safe, ratio, hour, 100 + t));
        return new Result(mode.name(), safe ? "SAFE" : "UNSAFE", ratio, hour,
                avg(rs, Result::emergencyAvgWait), avg(rs, Result::emergencyP95Wait), avg(rs, Result::routineAvgWait),
                avg(rs, Result::throughput), avg(rs, Result::conflicts), avg(rs, Result::doubleBookings),
                avg(rs, Result::rejected));
    }

    private static double avg(List<Result> rs, ToDoubleFunction<Result> f) {
        return rs.stream().mapToDouble(f).average().orElse(0);
    }

    public static List<Result> runAll(int trials) throws InterruptedException {
        List<Result> all = new ArrayList<>();
        // 1) Policy comparison at 10:00 (morning shift) for three emergency rates
        for (double ratio : new double[]{0.05, 0.10, 0.20}) {
            for (SchedulingMode m : SchedulingMode.values()) all.add(average(m, true, ratio, 10, trials));
        }
        // 2) Time of day: evening and night (thinner staffing) at 10% emergencies
        for (int hour : new int[]{18, 2}) {
            for (SchedulingMode m : SchedulingMode.values()) all.add(average(m, true, 0.10, hour, trials));
        }
        // 3) Unsafe vs safe slot booking
        all.add(average(SchedulingMode.PRIORITY_ER_TEAM, false, 0.10, 10, trials));
        return all;
    }

    public static void print(List<Result> rs) {
        System.out.printf("%-17s %-7s %6s %5s | %10s %10s %10s | %9s | %9s %7s %6s%n",
                "Mode", "Booking", "Emerg%", "Hour", "EmgAvg(ms)", "EmgP95(ms)", "RtnAvg(ms)", "Thru(/s)", "Conflicts", "DblBook", "Reject");
        for (Result r : rs) {
            System.out.printf("%-17s %-7s %5.0f%% %02d:00 | %10.1f %10.1f %10.1f | %9.1f | %9.1f %7.1f %6.1f%n",
                    r.mode(), r.booking(), r.emergencyRatio() * 100, r.hour(), r.emergencyAvgWait(), r.emergencyP95Wait(),
                    r.routineAvgWait(), r.throughput(), r.conflicts(), r.doubleBookings(), r.rejected());
        }
    }

    public static void writeCsv(List<Result> rs, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("mode,booking,emergency_ratio,hour,emg_avg_wait_ms,emg_p95_wait_ms,routine_avg_wait_ms,throughput_per_s,conflicts,double_bookings,rejected");
            rs.forEach(r -> w.printf("%s,%s,%.2f,%d,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f%n", r.mode(), r.booking(),
                    r.emergencyRatio(), r.hour(), r.emergencyAvgWait(), r.emergencyP95Wait(), r.routineAvgWait(),
                    r.throughput(), r.conflicts(), r.doubleBookings(), r.rejected()));
        }
    }
}

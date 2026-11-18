package hospital;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-doctor slot calendar for routine appointments. A doctor only has bookable slots inside
 * their own shift; the emergency team takes walk-ins and has no routine slots.
 *
 * safe=true  : check-and-book is atomic (one lock per doctor). A clash is detected and the
 *              request moves to the next free slot; this is counted as a "conflict".
 * safe=false : naive check-then-act with no lock. Two threads can both see a slot as free and
 *              both book it, producing a double booking (the race condition being studied).
 *
 * The index of a doctor here is the index in the roster list given to the constructor.
 */
public class SlotBook {
    private final boolean safe;
    private final boolean[][] valid;
    private final int[][] occupancy;
    private final ReentrantLock[] locks;

    private final AtomicInteger conflicts = new AtomicInteger();
    private final AtomicInteger doubleBookings = new AtomicInteger();

    public SlotBook(List<Doctor> doctors, boolean safe) {
        this.safe = safe;
        int n = doctors.size();
        this.valid = new boolean[n][SimClock.SLOTS_PER_DAY];
        this.occupancy = new int[n][SimClock.SLOTS_PER_DAY];
        this.locks = new ReentrantLock[n];
        for (int d = 0; d < n; d++) {
            locks[d] = new ReentrantLock();
            for (int s = 0; s < SimClock.SLOTS_PER_DAY; s++) {
                valid[d][s] = !doctors.get(d).isEmergencyTeam() && doctors.get(d).coversSlot(s);
            }
        }
    }

    /** Books a slot near {@code preferred} inside the doctor's shift; returns it, or -1 if the shift is full. */
    public int book(int doctorIdx, int preferred) {
        return safe ? bookSafe(doctorIdx, preferred) : bookUnsafe(doctorIdx, preferred);
    }

    private int bookSafe(int d, int preferred) {
        int n = SimClock.SLOTS_PER_DAY;
        locks[d].lock();
        try {
            for (int i = 0; i < n; i++) {
                int s = (preferred + i) % n;
                if (!valid[d][s]) continue;
                if (occupancy[d][s] == 0) {
                    occupancy[d][s] = 1;
                    return s;
                }
                if (i == 0) conflicts.incrementAndGet(); // preferred slot was already taken
            }
            return -1;
        } finally {
            locks[d].unlock();
        }
    }

    private int bookUnsafe(int d, int preferred) {
        int n = SimClock.SLOTS_PER_DAY;
        int first = -1;
        for (int i = 0; i < n; i++) {
            int s = (preferred + i) % n;
            if (valid[d][s]) { first = s; break; }
        }
        if (first < 0) return -1;

        if (occupancy[d][first] == 0) {                  // check ...
            LockSupport.parkNanos(20_000);               // widen the race window (~20 us)
            synchronized (this) {                        // ... then act (not atomic with the check)
                occupancy[d][first]++;
                if (occupancy[d][first] > 1) {
                    doubleBookings.incrementAndGet();
                    conflicts.incrementAndGet();
                }
            }
            return first;
        }
        conflicts.incrementAndGet();
        for (int i = 1; i < n; i++) {
            int s = (first + i) % n;
            if (valid[d][s] && occupancy[d][s] == 0) {
                synchronized (this) { occupancy[d][s]++; }
                return s;
            }
        }
        return -1;
    }

    public int getConflicts() { return conflicts.get(); }
    public int getDoubleBookings() { return doubleBookings.get(); }
}

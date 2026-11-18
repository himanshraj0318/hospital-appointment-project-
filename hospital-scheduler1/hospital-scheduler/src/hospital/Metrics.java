package hospital;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.DoubleStream;

/** Collects completed appointments and computes delay / throughput statistics with Java Streams. */
public class Metrics {
    private final List<Appointment> completed = new CopyOnWriteArrayList<>();
    private volatile long firstArrivalNs = Long.MAX_VALUE;
    private volatile long lastEndNs = 0;

    public synchronized void record(Appointment a) {
        completed.add(a);
        firstArrivalNs = Math.min(firstArrivalNs, a.getArrivalNs());
        lastEndNs = Math.max(lastEndNs, a.getEndNs());
    }

    private DoubleStream waits(Priority p) {
        return completed.stream().filter(a -> a.getPriority() == p).mapToDouble(Appointment::waitMs);
    }

    public double avgWaitMs(Priority p) { return waits(p).average().orElse(0); }

    public double p95WaitMs(Priority p) {
        double[] w = waits(p).sorted().toArray();
        if (w.length == 0) return 0;
        return w[(int) Math.min(w.length - 1, Math.ceil(0.95 * w.length) - 1)];
    }

    public int count(Priority p) { return (int) completed.stream().filter(a -> a.getPriority() == p).count(); }

    public int completedCount() { return completed.size(); }

    /** Completed appointments per second between the first arrival and the last completion. */
    public double throughputPerSec() {
        if (completed.isEmpty()) return 0;
        double secs = (lastEndNs - firstArrivalNs) / 1e9;
        return secs <= 0 ? 0 : completed.size() / secs;
    }
}

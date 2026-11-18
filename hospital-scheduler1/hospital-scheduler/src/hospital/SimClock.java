package hospital;

/**
 * Simulated hospital clock. Can run faster than real time (for the GUI demo) or be frozen at a
 * fixed time of day (speed 0, used by the benchmark so every run is repeatable).
 */
public class SimClock {
    public static final int SLOT_MINUTES = 3;
    public static final int SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES;

    private long baseNs = System.nanoTime();
    private double baseMinute;
    private double minutesPerSecond;

    public SimClock(double startMinute, double minutesPerSecond) {
        this.baseMinute = startMinute;
        this.minutesPerSecond = minutesPerSecond;
    }

    public synchronized double minuteOfDay() {
        double m = baseMinute + (System.nanoTime() - baseNs) / 1e9 * minutesPerSecond;
        m %= 1440;
        return m < 0 ? m + 1440 : m;
    }

    public synchronized void set(double minuteOfDay, double minutesPerSecond) {
        this.baseNs = System.nanoTime();
        this.baseMinute = minuteOfDay;
        this.minutesPerSecond = minutesPerSecond;
    }

    public void setSpeed(double minutesPerSecond) { set(minuteOfDay(), minutesPerSecond); }

    public void setMinute(double minuteOfDay) { set(minuteOfDay, getSpeed()); }

    public synchronized double getSpeed() { return minutesPerSecond; }

    public int currentSlot() { return slotOf(minuteOfDay()); }

    public static int slotOf(double minuteOfDay) { return (int) (minuteOfDay / SLOT_MINUTES) % SLOTS_PER_DAY; }

    public static String format(double minuteOfDay) {
        int m = (int) minuteOfDay;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    public static String slotLabel(int slot) { return format((double) slot * SLOT_MINUTES); }
}

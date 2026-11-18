package hospital;

/** A working-hours window. Overnight shifts (start hour greater than end hour) wrap past midnight. */
public record Shift(String name, int startHour, int endHour) {
    public static final Shift MORNING = new Shift("Morning", 6, 14);
    public static final Shift EVENING = new Shift("Evening", 14, 22);
    public static final Shift NIGHT = new Shift("Night", 22, 6);
    public static final Shift ALL_DAY = new Shift("24h on call", 0, 24);

    /** True if the given minute of the day (0-1439) falls inside this shift. */
    public boolean covers(double minuteOfDay) {
        if (startHour == 0 && endHour == 24) return true;
        double s = startHour * 60.0, e = endHour * 60.0;
        return s < e ? (minuteOfDay >= s && minuteOfDay < e) : (minuteOfDay >= s || minuteOfDay < e);
    }

    @Override
    public String toString() {
        return this == ALL_DAY || (startHour == 0 && endHour == 24)
                ? name : String.format("%s %02d:00-%02d:00", name, startHour, endHour % 24);
    }
}

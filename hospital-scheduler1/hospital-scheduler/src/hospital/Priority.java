package hospital;

/** Urgency level of an appointment. Lower rank = served first in priority mode. */
public enum Priority {
    EMERGENCY(0), ROUTINE(1);

    private final int rank;

    Priority(int rank) { this.rank = rank; }

    public int rank() { return rank; }
}

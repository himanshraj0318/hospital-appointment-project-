package hospital;

/** The three policies compared in the experiments. */
public enum SchedulingMode {
    /** Baseline: first come, first served; emergencies wait like everyone else. */
    FIFO(false, false),
    /** Emergencies jump the queue of the on-duty department doctor. */
    PRIORITY(true, false),
    /** Priority queues plus the five special doctors who handle only emergencies. */
    PRIORITY_ER_TEAM(true, true);

    private final boolean priority;
    private final boolean emergencyTeam;

    SchedulingMode(boolean priority, boolean emergencyTeam) {
        this.priority = priority;
        this.emergencyTeam = emergencyTeam;
    }

    public boolean usesPriority() { return priority; }
    public boolean usesEmergencyTeam() { return emergencyTeam; }
}

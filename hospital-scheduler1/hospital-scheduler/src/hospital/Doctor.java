package hospital;

/** Inheritance: a Doctor is a Person who works in one department during one shift. */
public class Doctor extends Person {
    private final Department department;
    private final Shift shift;
    private final boolean emergencyTeam;

    public Doctor(int id, String name, Department department, Shift shift, boolean emergencyTeam) {
        super(id, name);
        this.department = department;
        this.shift = shift;
        this.emergencyTeam = emergencyTeam;
    }

    public Department getDepartment() { return department; }
    public Shift getShift() { return shift; }
    public boolean isEmergencyTeam() { return emergencyTeam; }

    public boolean onDuty(double minuteOfDay) { return shift.covers(minuteOfDay); }

    public boolean coversSlot(int slot) { return shift.covers((double) slot * SimClock.SLOT_MINUTES); }

    @Override
    public String describe() { return "Dr. " + getName() + " - " + department + " (" + shift + ")"; }
}

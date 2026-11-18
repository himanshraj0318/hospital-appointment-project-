package hospital;

import java.util.Arrays;
import java.util.List;

/** Hospital departments. EMERGENCY is the wing staffed by the special 24-hour emergency team. */
public enum Department {
    GENERAL("General Medicine"),
    CARDIOLOGY("Cardiology"),
    ORTHOPEDICS("Orthopedics"),
    PEDIATRICS("Pediatrics"),
    NEUROLOGY("Neurology"),
    GYNECOLOGY("Gynecology"),
    EMERGENCY("Emergency Medicine");

    private final String label;

    Department(String label) { this.label = label; }

    public String label() { return label; }

    /** Departments that take routine (outpatient) appointments. */
    public static List<Department> clinical() {
        return Arrays.stream(values()).filter(d -> d != EMERGENCY).toList();
    }

    @Override
    public String toString() { return label; }
}

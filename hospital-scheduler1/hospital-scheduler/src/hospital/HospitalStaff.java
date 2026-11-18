package hospital;

import java.util.ArrayList;
import java.util.List;

/**
 * The hospital roster.
 *  - Six clinical departments, each with a Morning (06-14) and Evening (14-22) doctor.
 *  - General Medicine and Pediatrics also keep a Night (22-06) doctor, so the hospital is covered all day.
 *  - Five special Emergency Medicine doctors are available 24 hours for emergency treatment.
 */
public final class HospitalStaff {
    private HospitalStaff() {}

    public static List<Doctor> build() {
        List<Doctor> list = new ArrayList<>();
        int[] id = {1};

        add(list, id, Department.GENERAL, "Rao", "Mehta", "Iyer");
        add(list, id, Department.CARDIOLOGY, "Sharma", "Nair", null);
        add(list, id, Department.ORTHOPEDICS, "Gupta", "Reddy", null);
        add(list, id, Department.PEDIATRICS, "Kapoor", "Das", "Menon");
        add(list, id, Department.NEUROLOGY, "Singh", "Verma", null);
        add(list, id, Department.GYNECOLOGY, "Bose", "Pillai", null);

        for (String n : new String[]{"Khan", "Joshi", "Patel", "Roy", "Fernandes"}) {
            list.add(new Doctor(id[0]++, n, Department.EMERGENCY, Shift.ALL_DAY, true));
        }
        return list;
    }

    private static void add(List<Doctor> list, int[] id, Department d, String morning, String evening, String night) {
        list.add(new Doctor(id[0]++, morning, d, Shift.MORNING, false));
        list.add(new Doctor(id[0]++, evening, d, Shift.EVENING, false));
        if (night != null) list.add(new Doctor(id[0]++, night, d, Shift.NIGHT, false));
    }

    /** Departments that have a regular doctor on duty at the given minute of the day. */
    public static List<Department> openDepartments(List<Doctor> doctors, double minuteOfDay) {
        return Department.clinical().stream()
                .filter(dep -> doctors.stream().anyMatch(d -> !d.isEmergencyTeam()
                        && d.getDepartment() == dep && d.onDuty(minuteOfDay)))
                .toList();
    }

    public static long regularOnDuty(List<Doctor> doctors, double minuteOfDay) {
        return doctors.stream().filter(d -> !d.isEmergencyTeam() && d.onDuty(minuteOfDay)).count();
    }
}

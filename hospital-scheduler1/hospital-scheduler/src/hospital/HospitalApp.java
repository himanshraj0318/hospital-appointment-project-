package hospital;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Random;

/**
 * Swing GUI: book routine appointments by department and time, raise emergencies (served by the
 * 24-hour emergency team), watch who is on duty, and run the benchmark.
 */
public class HospitalApp extends JFrame {
    private final SimClock clock;
    private final Scheduler scheduler;
    private final Repository<Appointment> repo;
    private final List<Doctor> doctors;

    private final JTextField nameField = new JTextField(12);
    private final JSpinner ageSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 120, 1));
    private final JComboBox<Department> deptBox = new JComboBox<>(Department.clinical().toArray(new Department[0]));
    private final JComboBox<String> timeBox = new JComboBox<>();
    private final JComboBox<String> speedBox = new JComboBox<>(new String[]{"Paused", "1 min/s", "6 min/s", "20 min/s", "60 min/s"});
    private final JSpinner hourSpinner = new JSpinner(new SpinnerNumberModel(8, 0, 23, 1));
    private final JLabel clockLabel = new JLabel("--:--", SwingConstants.CENTER);

    private final DefaultTableModel apptModel = new DefaultTableModel(
            new String[]{"ID", "Patient", "Department", "Doctor", "Type", "Slot", "Status", "Wait (ms)"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final DefaultTableModel doctorModel = new DefaultTableModel(
            new String[]{"Doctor", "Department", "Shift", "On duty", "Queue", "Busy"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel statusBar = new JLabel(" ");
    private final Random rnd = new Random();

    public HospitalApp(Repository<Appointment> repo) {
        this(repo, null, null);
    }

    public HospitalApp(Repository<Appointment> repo, Scheduler scheduler, SimClock clock) {
        super("Hospital Appointment & Emergency Scheduler");
        this.repo = repo;
        this.clock = clock != null ? clock : new SimClock(8 * 60, 6);
        this.doctors = scheduler != null ? scheduler.getDoctors() : HospitalStaff.build();
        this.scheduler = scheduler != null ? scheduler : new Scheduler(this.doctors, SchedulingMode.PRIORITY_ER_TEAM,
                new SlotBook(this.doctors, true), repo, this.clock);

        for (int m = 0; m < 24 * 60; m += 30) timeBox.addItem(SimClock.format(m));
        timeBox.setSelectedItem("09:00");
        speedBox.setSelectedItem("6 min/s");
        speedBox.addActionListener(e -> clock.setSpeed(speedFromBox()));

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildForm(), BorderLayout.WEST);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled(new JScrollPane(new JTable(apptModel)), "Appointments"),
                titled(new JScrollPane(new JTable(doctorModel)), "Doctors (emergency team on 24h call)"));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        setSize(1120, 680);
        setLocationRelativeTo(null);

        new javax.swing.Timer(300, e -> refresh()).start();
    }

    private static JComponent titled(JComponent c, String title) {
        c.setBorder(BorderFactory.createTitledBorder(title));
        return c;
    }

    private double speedFromBox() {
        return switch ((String) speedBox.getSelectedItem()) {
            case "1 min/s" -> 1;
            case "6 min/s" -> 6;
            case "20 min/s" -> 20;
            case "60 min/s" -> 60;
            default -> 0;
        };
    }

    private JPanel buildForm() {
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        clockLabel.setFont(clockLabel.getFont().deriveFont(Font.BOLD, 28f));
        p.add(new JLabel("Hospital clock"));
        p.add(clockLabel);
        p.add(speedBox);
        JPanel jump = new JPanel(new BorderLayout(4, 0));
        jump.add(hourSpinner, BorderLayout.CENTER);
        JButton set = new JButton("Set hour");
        set.addActionListener(e -> clock.setMinute((Integer) hourSpinner.getValue() * 60.0));
        jump.add(set, BorderLayout.EAST);
        p.add(jump);

        p.add(new JLabel("Patient name"));
        p.add(nameField);
        p.add(new JLabel("Age"));
        p.add(ageSpinner);
        p.add(new JLabel("Department"));
        p.add(deptBox);
        p.add(new JLabel("Preferred time"));
        p.add(timeBox);

        JButton routine = new JButton("Book routine");
        routine.addActionListener(e -> book(Priority.ROUTINE));
        JButton emergency = new JButton("EMERGENCY");
        emergency.setBackground(new Color(200, 40, 40));
        emergency.setForeground(Color.WHITE);
        emergency.addActionListener(e -> book(Priority.EMERGENCY));
        JButton demo = new JButton("Add 15 random patients");
        demo.addActionListener(e -> addDemoLoad());
        JButton bench = new JButton("Run benchmark");
        bench.addActionListener(e -> runBenchmark(bench));
        JButton delete = new JButton("Delete finished records");
        delete.addActionListener(e -> repo.findAll().stream()
                .filter(a -> a.getStatus() == Appointment.Status.DONE)
                .forEach(a -> repo.delete(a.getId())));

        p.add(routine); p.add(emergency); p.add(demo); p.add(bench); p.add(delete);
        return p;
    }

    private void book(Priority pr) {
        String name = nameField.getText().trim();
        if (name.isEmpty()) name = pr == Priority.EMERGENCY ? "Emergency-" + rnd.nextInt(100) : "Patient-" + rnd.nextInt(1000);
        try {
            String[] hm = ((String) timeBox.getSelectedItem()).split(":");
            int minute = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            Patient p = new Patient(rnd.nextInt(100000), name, (Integer) ageSpinner.getValue());
            int service = pr == Priority.EMERGENCY ? 3000 + rnd.nextInt(2000) : 2000 + rnd.nextInt(2000);
            Appointment a = new Appointment(p, (Department) deptBox.getSelectedItem(),
                    SimClock.slotOf(minute), pr, service);
            if (scheduler.submit(a) == null) JOptionPane.showMessageDialog(this, "No doctor or free slot available.");
            nameField.setText("");
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addDemoLoad() {
        List<Department> deps = Department.clinical();
        for (int i = 0; i < 15; i++) {
            Patient p = new Patient(rnd.nextInt(100000), "Patient-" + rnd.nextInt(1000), 18 + rnd.nextInt(60));
            int slot = (clock.currentSlot() + rnd.nextInt(30)) % SimClock.SLOTS_PER_DAY;
            scheduler.submit(new Appointment(p, deps.get(rnd.nextInt(deps.size())), slot, Priority.ROUTINE, 2000 + rnd.nextInt(2000)));
        }
    }

    private void runBenchmark(JButton button) {
        button.setEnabled(false);
        statusBar.setText("Running benchmark (about a minute)...");
        new SwingWorker<List<Benchmark.Result>, Void>() {
            @Override protected List<Benchmark.Result> doInBackground() throws Exception { return Benchmark.runAll(2); }
            @Override protected void done() {
                button.setEnabled(true);
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Benchmark.Result r : get()) {
                        sb.append(String.format("%-17s %-6s %3.0f%% %02d:00  emergency wait %6.1f ms | routine wait %6.1f ms | %5.0f/s | conflicts %.0f | double-bookings %.0f%n",
                                r.mode(), r.booking(), r.emergencyRatio() * 100, r.hour(), r.emergencyAvgWait(),
                                r.routineAvgWait(), r.throughput(), r.conflicts(), r.doubleBookings()));
                    }
                    JTextArea area = new JTextArea(sb.toString());
                    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    area.setEditable(false);
                    JOptionPane.showMessageDialog(HospitalApp.this, new JScrollPane(area), "Benchmark results", JOptionPane.PLAIN_MESSAGE);
                    statusBar.setText("Benchmark finished.");
                } catch (Exception ex) {
                    statusBar.setText("Benchmark failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private Doctor doctorById(int id) {
        return doctors.stream().filter(d -> d.getId() == id).findFirst().orElse(null);
    }

    private void refresh() {
        double now = clock.minuteOfDay();
        clockLabel.setText(SimClock.format(now));

        apptModel.setRowCount(0);
        for (Appointment a : repo.findAll()) {
            Doctor d = doctorById(a.getDoctorId());
            apptModel.addRow(new Object[]{a.getId(), a.getPatient().getName(), a.getDepartment(),
                    d == null ? "-" : "Dr. " + d.getName(), a.getPriority(), SimClock.slotLabel(a.getSlot()),
                    a.getStatus(), a.getStatus() == Appointment.Status.WAITING ? "-" : String.format("%.0f", a.waitMs())});
        }

        doctorModel.setRowCount(0);
        for (int i = 0; i < doctors.size(); i++) {
            Doctor d = doctors.get(i);
            doctorModel.addRow(new Object[]{"Dr. " + d.getName() + (d.isEmergencyTeam() ? "  [ER]" : ""),
                    d.getDepartment(), d.getShift(), d.onDuty(now) ? "ON" : "off",
                    scheduler.queueLength(i), scheduler.isBusy(i) ? "yes" : "-"});
        }

        Metrics m = scheduler.getMetrics();
        statusBar.setText(String.format(" Store: %s | submitted %d | served %d | rejected %d | emergency avg wait %.0f ms | routine avg wait %.0f ms | slot conflicts %d",
                repo.name(), scheduler.getSubmitted(), scheduler.getCompleted(), scheduler.getRejected(),
                m.avgWaitMs(Priority.EMERGENCY), m.avgWaitMs(Priority.ROUTINE), scheduler.getSlotBook().getConflicts()));
    }
}

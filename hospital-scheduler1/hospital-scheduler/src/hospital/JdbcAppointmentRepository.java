package hospital;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC persistence (insert / retrieve / update / delete).
 * Works with any JDBC driver; the project uses SQLite: jdbc:sqlite:hospital.db
 * (put sqlite-jdbc-x.y.z.jar on the classpath).
 */
public class JdbcAppointmentRepository implements Repository<Appointment> {
    private final Connection conn;

    public JdbcAppointmentRepository(String url) throws SQLException {
        this.conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS appointment ("
                    + "id INTEGER PRIMARY KEY, patient_name TEXT, age INTEGER, department TEXT, doctor_id INTEGER, "
                    + "priority TEXT, slot INTEGER, status TEXT, wait_ms REAL)");
        }
    }

    @Override
    public synchronized void save(Appointment a) {
        String sql = "INSERT OR REPLACE INTO appointment(id,patient_name,age,department,doctor_id,priority,slot,status,wait_ms) VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, a);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("save failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void update(Appointment a) { save(a); }

    @Override
    public synchronized List<Appointment> findAll() {
        List<Appointment> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM appointment ORDER BY id")) {
            while (rs.next()) {
                Patient p = new Patient(0, rs.getString("patient_name"), rs.getInt("age"));
                Appointment a = new Appointment(p, Department.valueOf(rs.getString("department")),
                        rs.getInt("slot"), Priority.valueOf(rs.getString("priority")), 0);
                a.setDoctorId(rs.getInt("doctor_id"));
                a.setStatus(Appointment.Status.valueOf(rs.getString("status")));
                out.add(a);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("findAll failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public synchronized void delete(long id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM appointment WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("delete failed: " + e.getMessage(), e);
        }
    }

    private void bind(PreparedStatement ps, Appointment a) throws SQLException {
        ps.setLong(1, a.getId());
        ps.setString(2, a.getPatient().getName());
        ps.setInt(3, a.getPatient().getAge());
        ps.setString(4, a.getDepartment().name());
        ps.setInt(5, a.getDoctorId());
        ps.setString(6, a.getPriority().name());
        ps.setInt(7, a.getSlot());
        ps.setString(8, a.getStatus().name());
        ps.setDouble(9, a.getStatus() == Appointment.Status.WAITING ? 0 : a.waitMs());
    }

    @Override public String name() { return "SQLite (JDBC)"; }
}

package hospital;

import java.util.concurrent.atomic.AtomicLong;

/** One request for a doctor's time. Timing fields are written by worker threads, hence volatile. */
public class Appointment {
    public enum Status { WAITING, IN_PROGRESS, DONE }

    private static final AtomicLong ID_GEN = new AtomicLong(1);

    private final long id = ID_GEN.getAndIncrement();
    private final Patient patient;
    private final Department department;
    private final Priority priority;
    private final int serviceMs;

    private volatile int doctorId;      // assigned by the scheduler
    private volatile int slot;          // preferred slot on input, booked slot after submit
    private volatile long seq;
    private volatile long arrivalNs;
    private volatile long startNs;
    private volatile long endNs;
    private volatile Status status = Status.WAITING;

    public Appointment(Patient patient, Department department, int slot, Priority priority, int serviceMs) {
        this.patient = patient;
        this.department = department;
        this.slot = slot;
        this.priority = priority;
        this.serviceMs = serviceMs;
    }

    public long getId() { return id; }
    public Patient getPatient() { return patient; }
    public Department getDepartment() { return department; }
    public Priority getPriority() { return priority; }
    public int getServiceMs() { return serviceMs; }
    public int getDoctorId() { return doctorId; }
    public void setDoctorId(int d) { this.doctorId = d; }
    public int getSlot() { return slot; }
    public void setSlot(int s) { this.slot = s; }
    public long getSeq() { return seq; }
    public void setSeq(long s) { this.seq = s; }
    public long getArrivalNs() { return arrivalNs; }
    public void setArrivalNs(long t) { this.arrivalNs = t; }
    public long getStartNs() { return startNs; }
    public void setStartNs(long t) { this.startNs = t; }
    public long getEndNs() { return endNs; }
    public void setEndNs(long t) { this.endNs = t; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }

    /** Time spent waiting in the queue, in milliseconds (valid once service has started). */
    public double waitMs() { return (startNs - arrivalNs) / 1_000_000.0; }
}

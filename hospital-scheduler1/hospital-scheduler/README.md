# Hospital Appointment and Emergency Scheduler (CSE 213 - Advanced Java)

Concurrent scheduler for a multi-department hospital. Doctors work in shifts across the whole day,
five special doctors are on call 24 hours for emergencies, and the system measures conflicts,
delay (queue wait) and throughput.

## Staffing model (HospitalStaff.java)
| Department | Morning 06-14 | Evening 14-22 | Night 22-06 |
|---|---|---|---|
| General Medicine | Rao | Mehta | Iyer |
| Cardiology | Sharma | Nair | - |
| Orthopedics | Gupta | Reddy | - |
| Pediatrics | Kapoor | Das | Menon |
| Neurology | Singh | Verma | - |
| Gynecology | Bose | Pillai | - |
| Emergency Medicine (24h on call) | Khan, Joshi, Patel, Roy, Fernandes | | |

Change names, departments or hours in `HospitalStaff.build()` only.

## Build and run (JDK 17+)
```
mkdir out
javac -d out src/hospital/*.java
java -cp out hospital.Main              # Launches Web Dashboard (http://localhost:8080) & Swing GUI
java -cp out hospital.Main --web        # Runs Web Server only (default port 8080, or --web 9000)
java -cp out hospital.Main --bench 5    # Experiment, writes results.csv
```

## Web Frontend Mission Control
The project includes a modern web dashboard in `src/hospital/web/index.html`:
- **Live Hospital Clock**: Speed controls (Paused, 1x, 6x, 20x, 60x) and shift hour scrubbers.
- **Triage & Emergency Dispatch**: Instant 1-click critical emergency button routing to the 24h on-call team.
- **Routine Outpatient Booking**: Match departments and shifts with automatic slot reservation.
- **Doctor Duty Board**: Real-time shifts, on-duty status, and active doctor queue lengths.
- **Live Queue Ledger**: Filterable by status and emergency priority with millisecond wait times.
- **Interactive Benchmark Suite**: Run concurrency experiments comparing FIFO, PRIORITY, and PRIORITY_ER_TEAM directly from the browser.
- **Dual-Mode Architecture**: Works connected to the live Java backend, or standalone in any modern browser by opening `src/hospital/web/index.html`.

## Enable the database (JDBC / SQLite)
Download `sqlite-jdbc-<version>.jar` (Maven Central, org.xerial) and put it on the classpath:
```
# Linux/Mac
java -cp "out:sqlite-jdbc.jar" hospital.Main
# Windows
java -cp "out;sqlite-jdbc.jar" hospital.Main
```
Without the jar the app falls back to an in-memory store and prints a notice.

## How scheduling works
- Routine request: pick a department and preferred time; a doctor of that department whose shift covers that time is chosen and a slot (3-minute granularity) inside the shift is booked.
- Emergency: goes to the least-loaded of the five emergency doctors and jumps the queue.
- Shift change: when a doctor's shift ends, the remaining queue is handed to an on-duty colleague in the same department.
- Slots reserve capacity; patients are then served first-available within the doctor's shift (they are not held until the exact slot time).
- Three policies are compared: FIFO, PRIORITY, PRIORITY_ER_TEAM.

## Files
| File | Role |
|---|---|
| Person, Patient, Doctor | abstraction, inheritance, polymorphism |
| Department, Shift, Priority, SchedulingMode | enums and a record |
| Appointment | encapsulation, volatile timing fields |
| Repository<T>, InMemoryRepository, JdbcAppointmentRepository | interface, generics, JDBC CRUD |
| HospitalStaff | roster of departments, shifts and the emergency team |
| SimClock | simulated or frozen time of day |
| SlotBook | per-doctor shift calendar; safe (locked) vs unsafe (race) booking |
| Scheduler | worker thread per doctor, PriorityBlockingQueue, shift handover |
| Metrics | Java Streams statistics (avg, p95, throughput) |
| Benchmark | multi-threaded experiment harness |
| HospitalApp, Main | Swing GUI and entry point |

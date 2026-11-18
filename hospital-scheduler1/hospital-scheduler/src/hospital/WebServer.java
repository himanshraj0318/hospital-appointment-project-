package hospital;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Built-in HTTP and REST API server for the Hospital Scheduler.
 * Implemented using standard java.net.ServerSocket with a concurrent thread pool.
 * Zero external dependencies; works universally across all JDK versions (17+).
 * Serves the modern responsive Web Frontend Dashboard and handles all scheduling APIs.
 */
public class WebServer {
    private final int port;
    private final Repository<Appointment> repo;
    private final SimClock clock;
    private final List<Doctor> doctors;
    private final SlotBook slotBook;
    private final Scheduler scheduler;
    private final Random rnd = new Random();
    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private Thread listenerThread;

    public WebServer(Repository<Appointment> repo, int port) {
        this.port = port;
        this.repo = repo;
        this.doctors = HospitalStaff.build();
        this.clock = new SimClock(8 * 60, 6);   // 08:00, 6 simulated minutes per second
        this.slotBook = new SlotBook(doctors, true);
        this.scheduler = new Scheduler(doctors, SchedulingMode.PRIORITY_ER_TEAM, slotBook, repo, clock);
    }

    public WebServer(Repository<Appointment> repo, Scheduler scheduler, SimClock clock, int port) {
        this.port = port;
        this.repo = repo;
        this.doctors = scheduler.getDoctors();
        this.clock = clock;
        this.slotBook = scheduler.getSlotBook();
        this.scheduler = scheduler;
    }

    public Scheduler getScheduler() { return scheduler; }
    public SimClock getClock() { return clock; }
    public List<Doctor> getDoctors() { return doctors; }
    public Repository<Appointment> getRepo() { return repo; }
    public int getPort() { return port; }

    public synchronized void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        listenerThread = new Thread(this::listenLoop, "web-server-listener");
        listenerThread.setDaemon(false); // Non-daemon keeps JVM alive
        listenerThread.start();

        System.out.println("==================================================================");
        System.out.println("  Hospital Web Dashboard running at: http://localhost:" + port);
        System.out.println("  Central Scheduling, Emergency Triage & Real-Time Analytics");
        System.out.println("==================================================================");
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Repository<Appointment> repo = new InMemoryRepository();
        WebServer ws = new WebServer(repo, port);
        ws.start();
        System.out.println("Server active. Press Ctrl+C to shut down.");
        while (true) {
            Thread.sleep(10000);
        }
    }

    private void listenLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (SocketException se) {
                if (!running) break;
            } catch (IOException e) {
                if (running) System.err.println("Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;

            String[] reqParts = reqLine.split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0].toUpperCase();
            String uri = reqParts[1];
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;

            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String k = line.substring(0, colon).trim().toLowerCase();
                    String v = line.substring(colon + 1).trim();
                    headers.put(k, v);
                    if (k.equals("content-length")) {
                        try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Read body
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int total = 0;
                while (total < contentLength) {
                    int r = reader.read(buf, total, contentLength - total);
                    if (r == -1) break;
                    total += r;
                }
                body = new String(buf, 0, total);
            }

            // CORS Preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendHttp(out, 204, "No Content", "text/plain", new byte[0]);
                return;
            }

            // Route Requests
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] html = loadWebIndex();
                sendHttp(out, 200, "OK", "text/html; charset=UTF-8", html);
                return;
            }

            if (path.equals("/api/status") && "GET".equals(method)) {
                sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleStatus().getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/api/appointments")) {
                if ("GET".equals(method)) {
                    sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleGetAppointments().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("POST".equals(method)) {
                    String resp = handlePostAppointment(body);
                    int code = resp.contains("\"success\":true") ? 200 : 400;
                    sendHttp(out, code, code == 200 ? "OK" : "Bad Request", "application/json; charset=UTF-8", resp.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("DELETE".equals(method)) {
                    sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleDeleteAppointments().getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            if (path.equals("/api/clock") && "POST".equals(method)) {
                sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleClock(body).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/api/demo") && "POST".equals(method)) {
                sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleDemo(body).getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/api/benchmark") && "POST".equals(method)) {
                sendHttp(out, 200, "OK", "application/json; charset=UTF-8", handleBenchmark(body).getBytes(StandardCharsets.UTF_8));
                return;
            }

            // 404
            String notFound = "{\"error\":\"Not Found\"}";
            sendHttp(out, 404, "Not Found", "application/json; charset=UTF-8", notFound.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            // connection reset or closed
        }
    }

    private void sendHttp(OutputStream out, int status, String msg, String contentType, byte[] payload) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        pw.print("HTTP/1.1 " + status + " " + msg + "\r\n");
        pw.print("Content-Type: " + contentType + "\r\n");
        pw.print("Content-Length: " + payload.length + "\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
        pw.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        if (payload.length > 0) {
            out.write(payload);
            out.flush();
        }
    }

    // ===================================================================================
    // API Implementations
    // ===================================================================================

    private String handleStatus() {
        double now = clock.minuteOfDay();
        Metrics m = scheduler.getMetrics();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"clock\":{")
                .append("\"minuteOfDay\":").append(String.format(Locale.US, "%.1f", now)).append(",")
                .append("\"formatted\":\"").append(escape(SimClock.format(now))).append("\",")
                .append("\"currentSlot\":").append(clock.currentSlot()).append(",")
                .append("\"speed\":").append(clock.getSpeed())
                .append("},");

        sb.append("\"metrics\":{")
                .append("\"submitted\":").append(scheduler.getSubmitted()).append(",")
                .append("\"completed\":").append(scheduler.getCompleted()).append(",")
                .append("\"rejected\":").append(scheduler.getRejected()).append(",")
                .append("\"conflicts\":").append(slotBook.getConflicts()).append(",")
                .append("\"doubleBookings\":").append(slotBook.getDoubleBookings()).append(",")
                .append("\"emergencyAvgWaitMs\":").append(String.format(Locale.US, "%.2f", m.avgWaitMs(Priority.EMERGENCY))).append(",")
                .append("\"emergencyP95WaitMs\":").append(String.format(Locale.US, "%.2f", m.p95WaitMs(Priority.EMERGENCY))).append(",")
                .append("\"routineAvgWaitMs\":").append(String.format(Locale.US, "%.2f", m.avgWaitMs(Priority.ROUTINE))).append(",")
                .append("\"throughput\":").append(String.format(Locale.US, "%.2f", m.throughputPerSec()))
                .append("},");

        sb.append("\"store\":\"").append(escape(repo.name())).append("\",");
        sb.append("\"mode\":\"").append(escape(scheduler.getMode().name())).append("\",");

        sb.append("\"doctors\":[");
        for (int i = 0; i < doctors.size(); i++) {
            Doctor d = doctors.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append("\"id\":").append(d.getId()).append(",")
                    .append("\"name\":\"").append(escape(d.getName())).append("\",")
                    .append("\"department\":\"").append(escape(d.getDepartment().label())).append("\",")
                    .append("\"shift\":\"").append(escape(d.getShift().toString())).append("\",")
                    .append("\"shiftName\":\"").append(escape(d.getShift().name())).append("\",")
                    .append("\"isEmergencyTeam\":").append(d.isEmergencyTeam()).append(",")
                    .append("\"onDuty\":").append(d.onDuty(now)).append(",")
                    .append("\"queueLength\":").append(scheduler.queueLength(i)).append(",")
                    .append("\"isBusy\":").append(scheduler.isBusy(i))
                    .append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private String handleGetAppointments() {
        List<Appointment> all = repo.findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < all.size(); i++) {
            Appointment a = all.get(i);
            Doctor d = doctorById(a.getDoctorId());
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append("\"id\":").append(a.getId()).append(",")
                    .append("\"patientName\":\"").append(escape(a.getPatient().getName())).append("\",")
                    .append("\"patientAge\":").append(a.getPatient().getAge()).append(",")
                    .append("\"department\":\"").append(escape(a.getDepartment().label())).append("\",")
                    .append("\"priority\":\"").append(a.getPriority().name()).append("\",")
                    .append("\"slot\":").append(a.getSlot()).append(",")
                    .append("\"slotTime\":\"").append(escape(SimClock.slotLabel(a.getSlot()))).append("\",")
                    .append("\"doctorId\":").append(a.getDoctorId()).append(",")
                    .append("\"doctorName\":\"").append(d == null ? "Unassigned" : "Dr. " + escape(d.getName())).append("\",")
                    .append("\"status\":\"").append(a.getStatus().name()).append("\",")
                    .append("\"waitMs\":").append(a.getStatus() == Appointment.Status.WAITING ? 0 : String.format(Locale.US, "%.1f", a.waitMs())).append(",")
                    .append("\"serviceMs\":").append(a.getServiceMs())
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String handlePostAppointment(String body) {
        String priorityStr = getJsonString(body, "priority", "ROUTINE");
        Priority priority = "EMERGENCY".equalsIgnoreCase(priorityStr) ? Priority.EMERGENCY : Priority.ROUTINE;

        String name = getJsonString(body, "name", "");
        if (name.isEmpty()) {
            name = priority == Priority.EMERGENCY ? "Emergency-" + rnd.nextInt(100) : "Patient-" + rnd.nextInt(1000);
        }
        int age = getJsonInt(body, "age", 30);

        String deptStr = getJsonString(body, "department", "GENERAL");
        Department dep = Department.GENERAL;
        for (Department d : Department.values()) {
            if (d.name().equalsIgnoreCase(deptStr) || d.label().equalsIgnoreCase(deptStr)) {
                dep = d;
                break;
            }
        }

        int slot;
        int reqSlot = getJsonInt(body, "slot", -1);
        String timeStr = getJsonString(body, "time", "");
        if (reqSlot >= 0) {
            slot = reqSlot;
        } else if (!timeStr.isEmpty() && timeStr.contains(":")) {
            String[] hm = timeStr.split(":");
            int minute = Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
            slot = SimClock.slotOf(minute);
        } else {
            slot = clock.currentSlot();
        }

        int service = priority == Priority.EMERGENCY ? 3000 + rnd.nextInt(2000) : 2000 + rnd.nextInt(2000);
        Patient patient = new Patient(rnd.nextInt(100000), name, age);
        Appointment appt = new Appointment(patient, dep, slot, priority, service);
        Appointment booked = scheduler.submit(appt);

        if (booked == null) {
            return "{\"success\":false,\"error\":\"No doctor or free slot available in this shift.\"}";
        }

        Doctor doc = doctorById(booked.getDoctorId());
        return "{"
                + "\"success\":true,"
                + "\"id\":" + booked.getId() + ","
                + "\"patientName\":\"" + escape(booked.getPatient().getName()) + "\","
                + "\"department\":\"" + escape(booked.getDepartment().label()) + "\","
                + "\"doctorName\":\"" + (doc == null ? "Unassigned" : "Dr. " + escape(doc.getName())) + "\","
                + "\"slotTime\":\"" + escape(SimClock.slotLabel(booked.getSlot())) + "\","
                + "\"priority\":\"" + booked.getPriority().name() + "\""
                + "}";
    }

    private String handleDeleteAppointments() {
        List<Appointment> doneList = repo.findAll().stream()
                .filter(a -> a.getStatus() == Appointment.Status.DONE)
                .toList();
        for (Appointment a : doneList) {
            repo.delete(a.getId());
        }
        return "{\"success\":true,\"deleted\":" + doneList.size() + "}";
    }

    private String handleClock(String body) {
        if (body.contains("\"speed\"")) {
            double speed = getJsonDouble(body, "speed", clock.getSpeed());
            clock.setSpeed(speed);
        }
        if (body.contains("\"minute\"")) {
            double min = getJsonDouble(body, "minute", clock.minuteOfDay());
            clock.setMinute(min);
        } else if (body.contains("\"hour\"")) {
            int hour = getJsonInt(body, "hour", (int) (clock.minuteOfDay() / 60));
            clock.setMinute(hour * 60.0);
        }

        double now = clock.minuteOfDay();
        return "{"
                + "\"success\":true,"
                + "\"minuteOfDay\":" + String.format(Locale.US, "%.1f", now) + ","
                + "\"formatted\":\"" + escape(SimClock.format(now)) + "\","
                + "\"speed\":" + clock.getSpeed() + ","
                + "\"currentSlot\":" + clock.currentSlot()
                + "}";
    }

    private String handleDemo(String body) {
        int count = getJsonInt(body, "count", 15);
        List<Department> deps = Department.clinical();
        int added = 0;

        for (int i = 0; i < count; i++) {
            Patient p = new Patient(rnd.nextInt(100000), "Patient-" + rnd.nextInt(1000), 18 + rnd.nextInt(60));
            int slot = (clock.currentSlot() + rnd.nextInt(30)) % SimClock.SLOTS_PER_DAY;
            Appointment a = new Appointment(p, deps.get(rnd.nextInt(deps.size())), slot, Priority.ROUTINE, 2000 + rnd.nextInt(2000));
            if (scheduler.submit(a) != null) added++;
        }

        return "{\"success\":true,\"added\":" + added + ",\"requested\":" + count + "}";
    }

    private String handleBenchmark(String body) {
        int trials = getJsonInt(body, "trials", 1);
        if (trials < 1) trials = 1;
        if (trials > 5) trials = 5;

        try {
            List<Benchmark.Result> results = Benchmark.runAll(trials);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"trials\":").append(trials).append(",\"results\":[");
            for (int i = 0; i < results.size(); i++) {
                Benchmark.Result r = results.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                        .append("\"mode\":\"").append(escape(r.mode())).append("\",")
                        .append("\"booking\":\"").append(escape(r.booking())).append("\",")
                        .append("\"emergencyRatio\":").append(r.emergencyRatio()).append(",")
                        .append("\"hour\":").append(r.hour()).append(",")
                        .append("\"emergencyAvgWait\":").append(String.format(Locale.US, "%.2f", r.emergencyAvgWait())).append(",")
                        .append("\"emergencyP95Wait\":").append(String.format(Locale.US, "%.2f", r.emergencyP95Wait())).append(",")
                        .append("\"routineAvgWait\":").append(String.format(Locale.US, "%.2f", r.routineAvgWait())).append(",")
                        .append("\"throughput\":").append(String.format(Locale.US, "%.2f", r.throughput())).append(",")
                        .append("\"conflicts\":").append(r.conflicts()).append(",")
                        .append("\"doubleBookings\":").append(r.doubleBookings()).append(",")
                        .append("\"rejected\":").append(r.rejected())
                        .append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private Doctor doctorById(int id) {
        for (Doctor d : doctors) {
            if (d.getId() == id) return d;
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String getJsonString(String json, String key, String defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return defaultVal;
    }

    private static double getJsonDouble(String json, String key, double defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static int getJsonInt(String json, String key, int defaultVal) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static byte[] loadWebIndex() {
        Path[] candidates = new Path[]{
                Path.of("web", "index.html"),
                Path.of("src", "hospital", "web", "index.html"),
                Path.of("..", "web", "index.html"),
                Path.of("c:", "Users", "Himanshu kumar", "Downloads", "hospital-scheduler1", "hospital-scheduler", "src", "hospital", "web", "index.html")
        };
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception ignored) {}
        }

        String fallback = """
                <!DOCTYPE html>
                <html>
                <head><title>Hospital Scheduler</title></head>
                <body style="font-family:sans-serif;padding:2rem;">
                  <h1>Hospital Scheduler API is Online</h1>
                  <p>web/index.html not found on disk. APIs are available at <code>/api/status</code>.</p>
                </body>
                </html>
                """;
        return fallback.getBytes(StandardCharsets.UTF_8);
    }
}

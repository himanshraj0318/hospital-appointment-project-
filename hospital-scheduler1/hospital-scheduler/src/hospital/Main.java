package hospital;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;

/**
 * Usage:
 *   java hospital.Main            -> Starts Web Server (http://localhost:8080) & Swing GUI
 *   java hospital.Main --web      -> Starts Web Server only (default port 8080 or --web 9000)
 *   java hospital.Main --bench    -> Headless benchmark experiment, prints table and writes results.csv
 */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--bench")) {
            int trials = args.length > 1 ? Integer.parseInt(args[1]) : 5;
            List<Benchmark.Result> results = Benchmark.runAll(trials);
            Benchmark.print(results);
            Benchmark.writeCsv(results, Path.of("results.csv"));
            System.out.println("\nSaved results.csv (" + trials + " trials per row)");
            return;
        }

        int webPort = 8080;
        boolean webOnly = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--web") || args[i].equalsIgnoreCase("--server")) {
                webOnly = true;
                if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
                    webPort = Integer.parseInt(args[++i]);
                }
            } else if (args[i].equalsIgnoreCase("--port") && i + 1 < args.length) {
                webPort = Integer.parseInt(args[++i]);
            }
        }

        Repository<Appointment> repo = openRepository();
        WebServer webServer = new WebServer(repo, webPort);
        webServer.start();

        if (!webOnly && !GraphicsEnvironment.isHeadless()) {
            try {
                SwingUtilities.invokeLater(() -> new HospitalApp(repo, webServer.getScheduler(), webServer.getClock()).setVisible(true));
            } catch (Throwable t) {
                System.out.println("Headless environment detected; Swing GUI omitted. Web frontend active.");
            }
        } else {
            System.out.println("Running in Web Server mode. Open http://localhost:" + webPort + " in your browser.");
        }
    }

    /** Uses SQLite through JDBC when the driver jar is present; otherwise falls back to memory. */
    private static Repository<Appointment> openRepository() {
        try {
            Class.forName("org.sqlite.JDBC");
            return new JdbcAppointmentRepository("jdbc:sqlite:hospital.db");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found on classpath; using in-memory store.");
        } catch (Exception e) {
            System.err.println("Database error (" + e.getMessage() + "); using in-memory store.");
        }
        return new InMemoryRepository();
    }
}

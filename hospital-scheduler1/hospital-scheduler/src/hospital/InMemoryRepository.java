package hospital;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe fallback store used when no JDBC driver is on the classpath, and in benchmarks. */
public class InMemoryRepository implements Repository<Appointment> {
    private final Map<Long, Appointment> store = new ConcurrentHashMap<>();

    @Override public void save(Appointment a) { store.put(a.getId(), a); }
    @Override public void update(Appointment a) { store.put(a.getId(), a); }
    @Override public void delete(long id) { store.remove(id); }

    @Override
    public List<Appointment> findAll() {
        List<Appointment> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparingLong(Appointment::getId));
        return list;
    }

    @Override public String name() { return "In-memory"; }
}

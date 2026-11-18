package hospital;

import java.util.List;

/** Generic data-access interface (interfaces + generics). */
public interface Repository<T> {
    void save(T item);
    void update(T item);
    List<T> findAll();
    void delete(long id);
    String name();
}

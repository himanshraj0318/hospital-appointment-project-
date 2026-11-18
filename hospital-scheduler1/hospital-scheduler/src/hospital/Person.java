package hospital;

/** Abstraction: common state and contract for everyone the system knows about. */
public abstract class Person {
    private final int id;
    private final String name;

    protected Person(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    /** Each subclass describes itself (runtime polymorphism). */
    public abstract String describe();

    @Override
    public String toString() { return describe(); }
}

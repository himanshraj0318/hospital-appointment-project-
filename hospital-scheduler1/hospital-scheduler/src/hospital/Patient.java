package hospital;

/** Inheritance: a Patient is a Person. */
public class Patient extends Person {
    private final int age;

    public Patient(int id, String name, int age) {
        super(id, name);
        this.age = age;
    }

    public int getAge() { return age; }

    @Override
    public String describe() { return "Patient " + getName() + " (" + age + ")"; }
}

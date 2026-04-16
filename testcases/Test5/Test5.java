
abstract class Animal5 {
    abstract void speak();
}

class Dog5 extends Animal5 {
    @Override
    public void speak() { System.out.println("Woof"); }
}

class Cat5 extends Animal5 {
    @Override
    public void speak() { System.out.println("Meow"); }
}

public class Test5 {
    public static void main(String[] args) {
        boolean flag = args.length > 0;    // runtime value — PTA treats as unknown

        Animal5 a;
        if (flag) {
            a = new Dog5();   // AllocSite<Dog5>
        } else {
            a = new Cat5();   // AllocSite<Cat5>
        }
        a.speak();            // CHA→{Dog5,Cat5}, PTA→{Dog5,Cat5} → BIMORPHIC
    }
}
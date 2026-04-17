// Test5: BIMORPHIC — two allocation sites, guarded dispatch rewrite.
//
// Runtime branch produces either Dog5 or Cat5 for variable a.
// CHA and PTA both give 2 targets → BIMORPHIC.
// Rewriter produces: instanceof Dog5 check + two direct specialinvoke calls.
// This removes the vtable lookup and replaces with predictable branch.
//
// Expected output: Meow  (no args → flag=false → Cat5)
// Expected transformation: a.speak() → guarded dispatch (instanceof + 2 direct calls)

abstract class Animal5 {
    abstract String speak();
}

class Dog5 extends Animal5 {
    @Override
    public String speak() { return "Woof"; }
}

class Cat5 extends Animal5 {
    @Override
    public String speak() { return "Meow"; }
}

public class Test5 {
    public static void main(String[] args) {
        boolean flag = args.length > 0;   // runtime value, PTA treats as unknown

        Animal5 a;
        if (flag) {
            a = new Dog5();    // AllocSite<Dog5>
        } else {
            a = new Cat5();    // AllocSite<Cat5>
        }

        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = a.speak();   // CHA→{Dog5,Cat5}, PTA→{Dog5,Cat5} → BIMORPHIC
        }
        System.out.println(result);  // Meow
    }
}

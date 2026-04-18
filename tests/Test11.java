// Test11: PTA flow-sensitivity — CHA=BI, VTA=BI, PTA=MONO per call site.
//
// Both Cat11 and Dog11 are concrete subtypes of Animal11 and are BOTH allocated.
// VTA is flow-insensitive: it sees both Cat11 and Dog11 assigned to variable 'a'
// at different points in the loop, so it gives {Cat11.sound, Dog11.sound} = BIMORPHIC
// for BOTH call sites.
//
// PTA is flow-sensitive: at the first a.sound() call, only Cat11 has been assigned
// (pts(a) = {Cat11}), so PTA gives MONO(Cat11). After a = new Dog11(), pts(a) = {Dog11},
// so the second a.sound() is MONO(Dog11).
//
// This is the classic case where flow sensitivity matters.
//
// Expected:
//   After CHA  : BIMORPHIC (both subtypes in hierarchy)
//   After VTA  : BIMORPHIC (both types assigned to 'a' in the loop)
//   After PTA  : MONO x2 (flow-sensitive: Cat first, Dog second)
//
// Expected output: Meow11 Woof11
// Expected transformation: both a.sound() calls inlined (Cat11.sound and Dog11.sound)

abstract class Animal11 {
    abstract String sound();
}

class Cat11 extends Animal11 {
    @Override
    public String sound() { return "Meow11"; }
}

class Dog11 extends Animal11 {
    @Override
    public String sound() { return "Woof11"; }
}

public class Test11 {
    public static void main(String[] args) {
        String r1 = null, r2 = null;
        for (int i = 0; i < 100000; i++) {
            Animal11 a = new Cat11();   // pts(a) = {Cat11} - strong update
            r1 = a.sound();             // CHA/VTA: BIMORPHIC, PTA: MONO(Cat11)
            a = new Dog11();            // pts(a) = {Dog11} - strong update
            r2 = a.sound();             // CHA/VTA: BIMORPHIC, PTA: MONO(Dog11)
        }
        System.out.println(r1 + " " + r2);  // Meow11 Woof11
    }
}

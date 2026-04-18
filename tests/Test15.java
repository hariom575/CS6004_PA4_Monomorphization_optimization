// Test15: Bimorphic -- benefits from guarded dispatch transformation.
//
// Animal15 has two concrete subtypes: Cat15 and Dog15.
// A Cat15 is stored at arr[0] and a Dog15 at arr[1]. The loop reads arr[0].
//
// Our PTA does NOT track array element contents. Array element loads
// (a = arr[i]) are treated conservatively as UNKNOWN, regardless of what
// was stored. So pts(a) = {UNKNOWN} at the call site, and PTA cannot narrow
// the dispatch. The site stays at CHA/VTA level: BIMORPHIC.
//
// k-obj also cannot help: the receiver came through an array load, not a field.
//
// PTA and k-obj add NO PRECISION improvement here (cannot narrow below BIMORPHIC),
// but the BIMORPHIC transformation itself IS applied and IS beneficial: guarded
// dispatch replaces the virtual call with an instanceof check + two direct static
// calls, allowing the JIT to eliminate the vtable lookup and inline each branch.
//
// Full precision (resolving to MONO) would require an array-element points-to
// analysis (tracking array index -> allocation site mappings), which is beyond
// the scope of this intra-proc PTA.
//
// Expected:
//   After CHA/VTA : BIMORPHIC (both Cat15 and Dog15 allocated)
//   After PTA     : BIMORPHIC (array load is UNKNOWN -- no improvement in precision)
//   After k-obj   : BIMORPHIC (receiver not from a field -- no improvement in precision)
//   Transformation: Guarded dispatch applied (instanceof + 2 static calls) -- real benefit
//
// Expected output: Meow15

abstract class Animal15 {
    abstract String sound();
}

class Cat15 extends Animal15 {
    @Override
    public String sound() { return "Meow15"; }
}

class Dog15 extends Animal15 {
    @Override
    public String sound() { return "Woof15"; }
}

public class Test15 {
    public static void main(String[] args) {
        Animal15[] arr = new Animal15[2];
        arr[0] = new Cat15();
        arr[1] = new Dog15();

        String result = null;
        for (int i = 0; i < 100000; i++) {
            Animal15 a = arr[0];    // array load -> PTA marks as UNKNOWN
            result = a.sound();     // BIMORPHIC: PTA cannot narrow array elements
        }
        System.out.println(result);  // Meow15
    }
}

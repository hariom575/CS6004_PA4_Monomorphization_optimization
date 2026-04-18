// Test15: No benefit -- objects stored and loaded through an array.
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
// Guarded dispatch is applied (BIMORPHIC), but this is the same result VTA
// alone would give. Our extra layers add no precision.
//
// Full precision here would require an array-element points-to analysis
// (tracking array index -> allocation site mappings), which is beyond
// the scope of this intra-proc PTA.
//
// Expected:
//   After CHA/VTA : BIMORPHIC (both Cat15 and Dog15 allocated)
//   After PTA     : BIMORPHIC (array load is UNKNOWN -- no improvement)
//   After k-obj   : BIMORPHIC (receiver is not a field -- no help)
//   Transformation: Guarded dispatch (same result as VTA alone)
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

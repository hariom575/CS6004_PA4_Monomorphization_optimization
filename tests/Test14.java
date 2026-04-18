// Test14: No benefit -- receiver from inter-procedural method return.
//
// Task14 has two concrete subtypes: QuickTask14 and SlowTask14.
// The receiver t is assigned from make(true), an inter-procedural call.
//
// Our intra-proc PTA conservatively marks method return values as UNKNOWN
// (it has no inter-proc summaries). So pts(t) = {UNKNOWN} at the call site,
// and PTA cannot narrow it. The result stays at CHA/VTA level: BIMORPHIC.
//
// k-obj also cannot help: the receiver t is not a stored field; it is a
// return value. k-obj only handles the this.field pattern.
//
// Guarded dispatch is applied (since the site is BIMORPHIC), but this is
// the same result that VTA alone would produce. Our additional analysis
// layers (PTA, k-obj) contribute no extra precision here.
//
// This demonstrates the fundamental limitation of intra-procedural analysis:
// it cannot see through method call boundaries without inter-proc summaries.
//
// Expected:
//   After CHA/VTA : BIMORPHIC (both QuickTask14 and SlowTask14 in hierarchy)
//   After PTA     : BIMORPHIC (return value is UNKNOWN -- no improvement)
//   After k-obj   : BIMORPHIC (receiver is not a field -- no help)
//   Transformation: Guarded dispatch (same result as VTA alone)
//
// Expected output: quick

abstract class Task14 {
    abstract String run();
}

class QuickTask14 extends Task14 {
    @Override
    public String run() { return "quick"; }
}

class SlowTask14 extends Task14 {
    @Override
    public String run() { return "slow"; }
}

public class Test14 {
    static Task14 make(boolean fast) {
        if (fast) return new QuickTask14();
        return new SlowTask14();
    }

    public static void main(String[] args) {
        Task14 t = make(true);   // inter-proc return value -> PTA marks as UNKNOWN
        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = t.run();    // VTA=BIMORPHIC; PTA=UNKNOWN; no precision gain
        }
        System.out.println(result);  // quick
    }
}

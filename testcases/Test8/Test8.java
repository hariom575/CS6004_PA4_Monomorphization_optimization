// Test8: k=2 two-level field chain.
//
// Outer8 holds an Inner8, which holds a Processor8.
// Context-insensitive PTA of Inner8.process() sees pts(this)=UNKNOWN.
// k=1 looks one level up: caller's state for outer.inner, still UNKNOWN
//     because outer itself was a parameter (UNKNOWN in intra-proc).
// k=2 looks TWO levels up: main's state for outer.inner.proc → {FastProc8}.
//
// This specifically tests that the k-obj walk reaches depth 2.
//
// Expected:
//   After CHA  : POLY ({FastProc8, SlowProc8})
//   After PTA  : POLY (receiver goes through two field reads → UNKNOWN)
//   After k-Obj (k=1): POLY (only one level reached)
//   After k-Obj (k=2): MONO (FastProc8.process)
//   Set K=2 in MonomorphizationTransformer (already the default).

interface Processor8 {
    String process(String s);
}

class FastProc8 implements Processor8 {
    @Override
    public String process(String s) { return s.toUpperCase(); }
}

class SlowProc8 implements Processor8 {
    @Override
    public String process(String s) {
        try { Thread.sleep(1); } catch (Exception e) {}
        return s.toLowerCase();
    }
}

class Inner8 {
    Processor8 proc;
    Inner8(Processor8 p) { this.proc = p; }

    void run(String s) {
        // PTA: pts(this.proc) = UNKNOWN (this=UNKNOWN)
        // k-obj depth=2: caller's callerState[outer.inner.proc]={FastProc8}
        String result = proc.process(s);
        System.out.println(result);
    }
}

class Outer8 {
    Inner8 inner;
    Outer8(Inner8 i) { this.inner = i; }

    void execute(String s) {
        inner.run(s);   // k-obj depth=1: look up callerState[this.inner]
    }
}

public class Test8 {
    public static void main(String[] args) {
        Processor8 p = new FastProc8();           // AllocSite<FastProc8>
        Inner8 i = new Inner8(p);                  // AllocSite<Inner8>
        Outer8 outer = new Outer8(i);              // AllocSite<Outer8>
        // main's state: outer.inner = {AllocSite<Inner8>}
        //               outer.inner.proc = {AllocSite<FastProc8>}
        outer.execute("hello");   // depth-2 chain → MONO with k=2
    }
}
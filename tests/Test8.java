// Test8: k=2 two-level field chain.
//
// Outer8 holds Inner8, which holds Processor8.
// PTA sees pts(this)=UNKNOWN for both Inner8.run() and Outer8.execute() → can't narrow.
// k=1 reaches one level: callerState[outer.inner] - but outer itself is a parameter of
//     Outer8.execute(), so still UNKNOWN from Outer8's perspective.
// k=2 reaches two levels from main(): main's state has:
//     outer.inner.proc = {AllocSite<FastProc8>} → MONO.
//
// Expected:
//   After CHA  : POLY or BIMORPHIC (FastProc8, SlowProc8)
//   After PTA  : same (field reads through unknown this)
//   After k-Obj (k=2): MONO → FastProc8.process
//
// Expected output: HELLO
// Expected transformation: proc.process(s) devirtualised to FastProc8.process

interface Processor8 {
    String process(String s);
}

class FastProc8 implements Processor8 {
    @Override
    public String process(String s) { return s.toUpperCase(); }
}

class SlowProc8 implements Processor8 {
    @Override
    public String process(String s) { return s.toLowerCase(); }
}

class Inner8 {
    Processor8 proc;
    Inner8(Processor8 p) { this.proc = p; }

    String run(String s) {
        return proc.process(s);   // PTA: pts(this.proc)=UNKNOWN (this=UNKNOWN)
    }
}

class Outer8 {
    Inner8 inner;
    Outer8(Inner8 i) { this.inner = i; }

    String execute(String s) {
        return inner.run(s);   // k-obj depth=1: callerState[this.inner]
    }
}

public class Test8 {
    public static void main(String[] args) {
        Processor8 p = new FastProc8();          // AllocSite<FastProc8>
        Inner8 i = new Inner8(p);                 // i.proc = {FastProc8}
        Outer8 outer = new Outer8(i);             // outer.inner = {Inner8}, outer.inner.proc = {FastProc8}
        String result = null;
        for (int iter = 0; iter < 100000; iter++) {
            result = outer.execute("hello");   // k=2 chain resolves to FastProc8.process → MONO
        }
        System.out.println(result);  // HELLO
    }
}

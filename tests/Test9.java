// Test9: Two containers, different types — start() inlined, engine.run() gets guarded dispatch.
//
// box1.start() and box2.start() dispatch to Holder9.start() — Holder9 has only
// one concrete subtype, so both are MONO(Holder9.start) → inlined into main.
//
// engine.run() inside Holder9.start() is a SINGLE call site shared by both callers.
// k-obj sees both callers contribute different field types
// (box1.engine={Red}, box2.engine={Blue}), so the site stays BIMORPHIC.
// Guarded dispatch (instanceof + 2 staticinvoke calls) is applied to Holder9.start.
// Since start() is inlined into main, the guarded dispatch code lives in main too.
//
// Expected:
//   After CHA/VTA : box1.start() MONO, box2.start() MONO (Holder9 has no subtypes)
//                   engine.run() in Holder9.start: BIMORPHIC (Red + Blue)
//   After PTA     : engine.run() BIMORPHIC (pts(this.engine) = UNKNOWN inside start())
//   After k-Obj   : engine.run() BIMORPHIC (both callers → 2 distinct field types)
//   Transformation: box1.start() and box2.start() → inlined (Holder9.start body)
//                   engine.run() in Holder9.start → guarded dispatch (2 staticinvokes)
//
// Expected output: Red Blue

interface Engine9 {
    String run();
}

class RedEngine9 implements Engine9 {
    @Override public String run() { return "Red"; }
}

class BlueEngine9 implements Engine9 {
    @Override public String run() { return "Blue"; }
}

class Holder9 {
    Engine9 engine;
    Holder9(Engine9 e) { this.engine = e; }

    String start() {
        return engine.run();   // k-obj looks at caller's field state → MONO per call site
    }
}

public class Test9 {
    public static void main(String[] args) {
        Holder9 box1 = new Holder9(new RedEngine9());    // callerState[box1.engine]={Red}
        Holder9 box2 = new Holder9(new BlueEngine9());   // callerState[box2.engine]={Blue}
        String r1 = null, r2 = null;
        for (int i = 0; i < 100000; i++) {
            r1 = box1.start();   // k-obj: box1.engine = {RedEngine9} → MONO
            r2 = box2.start();   // k-obj: box2.engine = {BlueEngine9} → MONO
        }
        System.out.println(r1 + " " + r2);  // Red Blue
    }
}

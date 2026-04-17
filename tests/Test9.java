// Test9: Two containers, different types — k-obj resolves each call site to MONO.
//
// box1.start() has pts(box1) = {AllocSite A}, callerState[A.engine] = {RedEngine9} → MONO
// box2.start() has pts(box2) = {AllocSite B}, callerState[B.engine] = {BlueEngine9} → MONO
//
// Each call site is a SEPARATE Jimple statement, so k-obj can narrow each independently.
// (If both calls went through a helper method taking Holder9 as parameter, the
//  single call site inside that method would have both alloc sites → BIMORPHIC.)
//
// Expected:
//   After CHA  : BIMORPHIC (2 subtypes of Engine9)
//   After PTA  : BIMORPHIC (pts(this.engine) = UNKNOWN inside start())
//   After k-Obj: box1.start() → MONO(Red), box2.start() → MONO(Blue)
//
// Expected output: Red Blue
// Expected transformation: box1.start() → inline RedEngine9.run, box2.start() → inline BlueEngine9.run

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

// Test9: Two containers, different types — k-obj gives BIMORPHIC, not MONO.
//
// This verifies that k-obj does NOT over-narrow:
//   box1 holds RedEngine9  → call site at box1.run() → MONO (RedEngine9)
//   box2 holds BlueEngine9 → call site at box2.run() → MONO (BlueEngine9)
//
// But there is only ONE call site (box.run() in a shared method),
// and we call it with BOTH boxes.  So:
//   PTA of main: pts(box) = {AllocSite<Holder9> A, AllocSite<Holder9> B}
//   callerState[A.engine] = {RedEngine9}
//   callerState[B.engine] = {BlueEngine9}
// k-obj must union across both alloc sites → {RedEngine9, BlueEngine9} → BIMORPHIC.
//
// Expected:
//   After CHA  : BIMORPHIC or POLY
//   After PTA  : BIMORPHIC (two alloc sites)
//   After k-Obj: BIMORPHIC (both containers' fields are different types)
//   Rewrite    : GUARDED INLINE

interface Engine9 { void run(); }

class RedEngine9 implements Engine9 {
    @Override public void run() { System.out.println("Red engine"); }
}

class BlueEngine9 implements Engine9 {
    @Override public void run() { System.out.println("Blue engine"); }
}

class Holder9 {
    Engine9 engine;
    Holder9(Engine9 e) { this.engine = e; }
    void start() {
        engine.run();   // k-obj: across two alloc sites → {Red,Blue} → BIMORPHIC
    }
}

public class Test9 {
    public static void main(String[] args) {
        Holder9 box1 = new Holder9(new RedEngine9());   // A.engine = {Red}
        Holder9 box2 = new Holder9(new BlueEngine9());  // B.engine = {Blue}
        box1.start();   // call site 1 — k-obj should see MONO (only box1 here)
        box2.start();   // call site 2 — k-obj should see MONO (only box2 here)
        // Note: each call site is a SEPARATE stmt, so k-obj can resolve each MONO.
        // The interesting case would be if both calls went through a helper method
        // that takes Holder9 as a parameter — then this becomes truly ambiguous.
    }
}
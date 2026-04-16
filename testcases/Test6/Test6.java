// Test6: Three allocation sites — POLY after PTA, type-test chain rewrite.
//
// CHA: Vehicle6 has subtypes {Car6, Bus6, Bike6} → 3 edges.
// PTA: switch on args[0] — all three branches reachable.
//      pts(v) = {AllocSite<Car6>, AllocSite<Bus6>, AllocSite<Bike6>} → 3 targets.
//
// Expected:
//   After CHA  : POLY (3 targets)
//   After PTA  : POLY (3 targets, all branches reachable)
//   Rewrite    : TYPE-TEST CHAIN
//                  if v instanceof Car6  → Car6.drive  directly
//                  if v instanceof Bus6  → Bus6.drive  directly
//                  else                  → Bike6.drive directly
//                  (no fallback virtual needed — we cover all 3)

abstract class Vehicle6 {
    abstract void drive();
}

class Car6 extends Vehicle6 {
    @Override public void drive() { System.out.println("Car driving"); }
}

class Bus6 extends Vehicle6 {
    @Override public void drive() { System.out.println("Bus driving"); }
}

class Bike6 extends Vehicle6 {
    @Override public void drive() { System.out.println("Bike riding"); }
}

public class Test6 {
    public static void main(String[] args) {
        int choice = args.length;   // runtime value
        System.out.println(choice);
        Vehicle6 v;
        switch (choice % 3) {
            case 0:  v = new Car6();  break;
            case 1:  v = new Bus6();  break;
            default: v = new Bike6(); break;
        }
        v.drive();   // CHA→{Car6,Bus6,Bike6}, PTA→{Car6,Bus6,Bike6} → POLY
    }
}
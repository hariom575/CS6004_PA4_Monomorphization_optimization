// Test6: POLY — three allocation sites, type-test chain rewrite.
//
// Vehicle6 has 3 concrete subtypes: Car6, Bus6, Bike6.
// CHA gives 3 edges. PTA sees switch on args.length so all 3 branches reachable.
// pts(v) = {Car6, Bus6, Bike6} → 3 targets → POLY.
// Rewriter produces: instanceof Car6 → Car6.drive; instanceof Bus6 → Bus6.drive;
//                    else → Bike6.drive (last arm via original virtual fallback).
//
// Expected output: Car  (no args → choice=0 → Car6)
// Expected transformation: v.drive() → type-test chain

abstract class Vehicle6 {
    abstract String drive();
}

class Car6 extends Vehicle6 {
    @Override public String drive() { return "Car"; }
}

class Bus6 extends Vehicle6 {
    @Override public String drive() { return "Bus"; }
}

class Bike6 extends Vehicle6 {
    @Override public String drive() { return "Bike"; }
}

public class Test6 {
    public static void main(String[] args) {
        int choice = args.length % 3;   // runtime value
        Vehicle6 v;
        switch (choice) {
            case 0:  v = new Car6();   break;
            case 1:  v = new Bus6();   break;
            default: v = new Bike6();  break;
        }
        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = v.drive();   // CHA→{Car6,Bus6,Bike6} → POLY
        }
        System.out.println(result);  // Car
    }
}

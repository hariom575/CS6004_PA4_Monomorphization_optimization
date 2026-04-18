// Test4: MONO — multiple call sites, each resolved independently.
//// This test case is covered by VTA to mono by just looking at right hand side of the new expression.
// Two Shape4 variables hold distinct allocation sites (Circle4, Square4).
// PTA tracks each allocation site separately.
// Both s.area() and s1.area() are MONO sites (each has only 1 concrete target).
//
// Expected output: 103.53975
// Expected transformation: s.area() inlined (Circle4), s1.area() inlined (Square4)

abstract class Shape4 {
    abstract double area();
}

class Circle4 extends Shape4 {
    double r;
    Circle4(double r) { this.r = r; }
    @Override
    public double area() { return 3.14159 * r * r; }
}

class Square4 extends Shape4 {
    double s;
    Square4(double s) { this.s = s; }
    @Override
    public double area() { return s * s; }
}

public class Test4 {
    public static void main(String[] args) {
        Shape4 s  = new Circle4(5.0);   // PTA: pts(s)  = {Circle4}
        Shape4 s1 = new Square4(5.0);   // PTA: pts(s1) = {Square4}
        double sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += s.area() + s1.area();   // (VTA resolve to) 2 MONO sites → both inlined 
        }
        System.out.println(sum / 100000);  // ~103.5 (floating point accumulation)
    }
}

// Test10: MEGA — five subtypes, no optimisation applied.
//
// With more than MEGA_THRESHOLD (4) targets, the type-test chain
// cost exceeds the vtable lookup cost.  The rewriter skips MEGA sites.
//
// This test verifies:
//   (a) Classification stops at MEGA and is reported correctly.
//   (b) The rewriter emits no transformation for this site.
//   (c) The Jimple output is unchanged for MEGA sites.
//
// Expected:
//   After CHA  : MEGA (5 edges)
//   After PTA  : MEGA (all 5 branches reachable)
//   Rewrite    : SKIPPED (no transformation)

abstract class Shape10 { abstract double area(); }

class Circle10    extends Shape10 { double r; Circle10(double r){this.r=r;} public double area(){return 3.14*r*r;} }
class Square10    extends Shape10 { double s; Square10(double s){this.s=s;} public double area(){return s*s;} }
class Triangle10  extends Shape10 { double b,h; Triangle10(double b,double h){this.b=b;this.h=h;} public double area(){return 0.5*b*h;} }
class Pentagon10  extends Shape10 { double s; Pentagon10(double s){this.s=s;} public double area(){return 1.72*s*s;} }
class Hexagon10   extends Shape10 { double s; Hexagon10(double s){this.s=s;} public double area(){return 2.6*s*s;} }

public class Test10 {
    public static void main(String[] args) {
        int choice = args.length;
        Shape10 s;
        switch (choice % 5) {
            case 0:  s = new Circle10(1);      break;
            case 1:  s = new Square10(1);      break;
            case 2:  s = new Triangle10(2,3);  break;
            case 3:  s = new Pentagon10(1);    break;
            default: s = new Hexagon10(1);     break;
        }
        // CHA and PTA both see 5 targets → MEGA → no rewrite
        System.out.println(s.area());
    }
}
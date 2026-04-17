// Test10: MEGA — five subtypes, no optimisation applied (negative testcase).
//
// Shape10 has 5 concrete subtypes. CHA and PTA both give 5 targets.
// 5 > MEGA_THRESHOLD(4), so no transformation is applied.
// This is a negative testcase to verify the rewriter correctly skips MEGA sites.
// Wall-clock time before and after should be same.
//
// Expected:
//   After CHA  : MEGA (5 edges)
//   After PTA  : MEGA (all 5 branches reachable)
//   Rewrite    : SKIPPED
//
// Expected output: 3.14  (no args → choice=0 → Circle10(1) → 3.14*1*1)
// Expected transformation: none

abstract class Shape10 { abstract double area(); }

class Circle10   extends Shape10 { double r; Circle10(double r){this.r=r;}   public double area(){ return 3.14*r*r; } }
class Square10   extends Shape10 { double s; Square10(double s){this.s=s;}   public double area(){ return s*s; } }
class Triangle10 extends Shape10 { double b,h; Triangle10(double b,double h){this.b=b;this.h=h;} public double area(){ return 0.5*b*h; } }
class Pentagon10 extends Shape10 { double s; Pentagon10(double s){this.s=s;} public double area(){ return 1.72*s*s; } }
class Hexagon10  extends Shape10 { double s; Hexagon10(double s){this.s=s;}  public double area(){ return 2.6*s*s; } }

public class Test10 {
    public static void main(String[] args) {
        int choice = args.length % 5;
        Shape10 s;
        switch (choice) {
            case 0:  s = new Circle10(1);      break;
            case 1:  s = new Square10(1);      break;
            case 2:  s = new Triangle10(2,3);  break;
            case 3:  s = new Pentagon10(1);    break;
            default: s = new Hexagon10(1);     break;
        }
        double sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += s.area();   // MEGA (5 targets) → no rewrite
        }
        System.out.println(sum / 100000);  // ~3.14 (floating point)
    }
}

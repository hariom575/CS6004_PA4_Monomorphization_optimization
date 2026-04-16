
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
        Shape4 s = new Circle4(5.0);  // PTA: pts(s)={Circle4}
        Shape4  s1 = new  Square4(5);
        double a = s.area();           // CHA→{Circle4,Square4}, PTA→{Circle4} MONO
        double b = s1.area();
        System.out.println(a + b);
    }
}
abstract class Base1 {
    abstract int compute();
}

class Only extends Base1 {
    @Override
    public int compute() {
        return 42;
    }
}

public class Test1 {
    public static void main(String[] args) {
        Base1 b = new Only();  // Only one concrete subtype of Base1
        int result = b.compute();  // Line: virtual call, CHA → {Only.compute} → MONO
        System.out.println(result);
    }
}
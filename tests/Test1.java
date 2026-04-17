// Test1: MONO — single concrete subtype, method gets inlined.
//
// Base1 has only one concrete subtype (Only). CHA sees 1 edge → MONO.
// b.compute() gets inlined directly, vtable lookup eliminated.
//
// Expected output: 42
// Expected transformation: b.compute() inlined (Only.compute body pasted in)

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
        Base1 b = new Only();   // only one concrete subtype of Base1
        long sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += b.compute();   // virtual call → CHA gives {Only.compute} → MONO → inlined
        }
        System.out.println(sum / 100000);  // 42
    }
}

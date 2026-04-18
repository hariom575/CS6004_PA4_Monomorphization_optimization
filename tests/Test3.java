// Test3: MONO — interface dispatch with single implementation.
//
// Printable3 has one implementing class (FastPrinter3).
// Interface invoke resolves to MONO via CHA/VTA.
// format() gets inlined because its body is small (< 30 stmts).
//
// Expected output: [FAST] hello
// Expected transformation: p.format("hello") inlined (FastPrinter3.format body pasted in)

interface Printable3 {
    String format(String msg);
}

class FastPrinter3 implements Printable3 {
    @Override
    public String format(String msg) {
        return "[FAST] " + msg;
    }
}

public class Test3 {
    public static void main(String[] args) {
        Printable3 p = new FastPrinter3();
        String result = null;
        for (int i = 0; i < 100000; i++) {
            result = p.format("hello");   // interface invoke → CHA as well as VTA gives {FastPrinter3.format} → MONO
        }
        System.out.println(result);  // [FAST] hello
    }
}

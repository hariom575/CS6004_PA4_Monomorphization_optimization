
interface Printable3 {
    void print(String msg);
}

class FastPrinter implements Printable3 {
    @Override
    public void print(String msg) {
        System.out.println("[FAST] " + msg);
    }
}

public class Test3 {
    public static void main(String[] args) {
        Printable3 p = new FastPrinter();
        p.print("hello");   // InterfaceInvoke, CHA → {FastPrinter.print} → MONO
    }
}
// Test12: Inline threshold boundary.
//
// Two call sites, both MONO:
//   (a) TinyWorker12.work()  — 3 statements → below threshold → INLINE
//   (b) BigWorker12.work()   — 35 statements → above threshold → DEVIRT only
//
// This verifies the INLINE_THRESHOLD=30 check in JimpleRewriter.
// After inlining TinyWorker12.work, its body appears directly in main.
// After devirtualising BigWorker12.work, a staticinvoke remains.
//
// Expected:
//   After CHA  : MONO=2 (each declared type has only one subtype)
//   After PTA  : MONO=2
//   Rewrite    :
//     TinyWorker12.work → INLINE  (body pasted into main)
//     BigWorker12.work  → DEVIRT  (virtualinvoke → staticinvoke)

abstract class Doer12 { abstract void work(); }

class TinyWorker12 extends Doer12 {
    // 3 Jimple stmts — well below threshold of 30
    @Override
    public void work() {
        System.out.println("tiny");
    }
}

class BigWorker12 extends Doer12 {
    // 35+ Jimple stmts — above threshold, devirt only
    @Override
    public void work() {
        int a = 1, b = 2, c = 3, d = 4, e = 5;
        int f = a + b;
        int g = c + d;
        int h = e + f;
        int i = g + h;
        int j = i * 2;
        int k = j - a;
        int l = k + b;
        int m = l * c;
        int n = m - d;
        int o = n + e;
        int p = o * f;
        int q = p - g;
        int r = q + h;
        int s = r - i;
        int t = s + j;
        int u = t * k;
        int v = u - l;
        int w = v + m;
        System.out.println("big: " + w);
    }
}

public class Test12 {
    public static void main(String[] args) {
        Doer12 tiny = new TinyWorker12();
        Doer12 big  = new BigWorker12();

        tiny.work();   // MONO → INLINE  (TinyWorker12 body pasted here)
        big.work();    // MONO → DEVIRT  (virtualinvoke → staticinvoke <BigWorker12: void work()>)
    }
}
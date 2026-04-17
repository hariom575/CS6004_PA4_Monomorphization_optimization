// Test12: Inline threshold boundary — MONO with small vs large callee.
//
// Two call sites, both MONO:
//   (a) TinyWorker12.work()  — few Jimple stmts → below threshold (30) → INLINE
//   (b) BigWorker12.work()   — many Jimple stmts → above threshold → DEVIRT only
//
// This tests that JimpleRewriter correctly applies inline vs devirt depending on
// callee body size. TinyWorker's body gets pasted in; BigWorker gets devirtualised.
//
// Expected:
//   After CHA  : MONO=4 (each Doer12 variable has exactly one concrete subtype)
//   Rewrite    : TinyWorker12.work → INLINE, BigWorker12.work → DEVIRT
//
// Expected output: 11  (TinyWorker returns 7, BigWorker returns 4; 7+4=11)
// Expected transformation: tiny.work() inlined, big.work() devirtualised

abstract class Doer12 { abstract int work(); }

class TinyWorker12 extends Doer12 {
    // 3 Jimple statements — well below inline threshold
    @Override
    public int work() {
        return 7;
    }
}

class BigWorker12 extends Doer12 {
    // many Jimple statements — above threshold, devirt only
    @Override
    public int work() {
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
        return w % 100;
    }
}

public class Test12 {
    public static void main(String[] args) {
        Doer12 tiny = new TinyWorker12();
        Doer12 big  = new BigWorker12();
        long sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += tiny.work();   // MONO → INLINE
            sum += big.work();    // MONO → DEVIRT
        }
        System.out.println(sum / 100000);  // 7 + 4 = 11
    }
}

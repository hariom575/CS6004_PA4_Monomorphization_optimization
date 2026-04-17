// Test11: MONO — PTA narrows CHA from 2 targets to 1.
//
// CHA sees both C.foo and D.foo as targets for c.foo(a).
// Since only new D() is allocated (new C() is commented out), PTA gives 1 target → MONO.
// This is similar to Test2 but uses a different class hierarchy.
//
// Expected:
//   After CHA  : BIMORPHIC (C.foo and D.foo)
//   After PTA  : MONO (only D() allocated)
//
// Expected output: 100
// Expected transformation: c.foo(a) devirtualised to D.foo

class A {
    int x;
}

class C {
    int foo(A a) {
        a.x = 99;
        return a.x;
    }
}

class D extends C {
    @Override
    int foo(A a) {
        a.x = 100;
        return a.x;
    }
}

public class Test11 {
    public static void main(String[] args) {
        A a = new A();
        C c = new D();   // only D allocated; if new C() were added, PTA gives BIMORPHIC
        // c = new C();  -- uncomment to see how PTA gives {C.foo, D.foo}
        long sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += c.foo(a);   // CHA→{C.foo,D.foo}, PTA→{D.foo} → MONO
        }
        System.out.println(sum / 100000);  // 100
    }
}

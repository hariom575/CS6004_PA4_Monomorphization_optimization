// Test2: MONO — PTA narrows CHA from 2 targets to 1.
//
// CHA sees both C2.foo and D2.foo as targets for c.foo(a).
// PTA observes only new D2() is allocated for c → narrows to MONO.
// The commented-out new C2() line shows what would cause BIMORPHIC.
//
// Expected output: 100
// Expected transformation: c.foo(a) devirtualised to D2.foo

class A2 {
    int x;
}

class C2 {
    int foo(A2 a) {
        a.x = 99;
        return a.x;
    }
}

class D2 extends C2 {
    @Override
    int foo(A2 a) {
        a.x = 100;
        return a.x;
    }
}

public class Test2 {
    public static void main(String[] args) {
        A2 a = new A2();
        C2 c = new D2();   // only D2 allocated; C2 branch is dead
        C2 c2 = new C2();// -- if uncommented, VTA gives {C2.foo, D2.foo}
        long sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += c.foo(a);   // VTA→{C2.foo,D2.foo}, PTA→{D2.foo} → MONO
        }
        System.out.println(sum / 100000);  // 100
    }
}

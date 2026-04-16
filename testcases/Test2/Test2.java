class A2 { int x; }

class C2 {
    void foo(A2 a) {
        a.x = 99;
    }
}

class D2 extends C2 {
    @Override
    void foo(A2 a) {
        System.out.println(a.x);
    }
}

public class Test2 {
    public static void main(String[] args) {
        A2 a = new A2();
        C2 c = new D2();    // Only D2 allocated; the C2 branch is gone.
        // C2 c2 = new C2(); — if this were here, PTA would give {C2, D2}
        c.foo(a);           // Line: CHA→{C2.foo, D2.foo}, PTA→{D2.foo} → MONO
    }
}
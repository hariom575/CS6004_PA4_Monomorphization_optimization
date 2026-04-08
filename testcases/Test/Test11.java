  class A {
    int x;
}

class C {
    void foo(A a) {
        a.x = 99;
    }
}

class D extends C {
    @Override
    void foo(A a) {
        System.out.println(a.x);
    }
}

public class Test11 {
    public static void main(String[] args) {
        A a = new A();   // create object of A
        C c = new D();   // O22
        int x = 9;
        // if(x==10){
        //     c = new C();
        // }
        c.foo(a);        // Line 23
    }
}
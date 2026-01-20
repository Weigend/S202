package com.example2;

/**
 * Top class in diamond dependency pattern (example2).
 * Depends on both B and C.
 */
public class A {
    private B b = new B();
    private C c = new C();
    
    public void methodA() {
        b.methodB();
        c.methodC();
    }
}

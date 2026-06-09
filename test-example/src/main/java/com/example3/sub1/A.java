package com.example3.sub1;

import com.example3.sub2.C;

/**
 * example3 package-cycle demo.
 * A depends on B (2 edges, sub1 internal) and on C (sub1->sub2, creates package cycle).
 */
public class A {
    private B b = new B();
    private C c = new C();

    public void doA() {
        b.doB();
        c.doC();
    }
}

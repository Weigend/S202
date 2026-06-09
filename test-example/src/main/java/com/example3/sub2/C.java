package com.example3.sub2;

import com.example3.sub1.B;

/**
 * example3 package-cycle demo.
 * C depends on D (2 edges: INSTANTIATES + CALLS) and on B (sub2->sub1).
 */
public class C {
    private D d = new D();
    private B b = new B();

    public void doC() {
        d.doD();
        b.doB();
    }
}

package com.example3.sub2;

import com.example3.sub1.A;
import com.example3.sub1.B;

/**
 * example3 package-cycle demo.
 * C depends on D (2 edges: field + call) and on B (sub2->sub1).
 * Cycle: A -> C -> D -> A
 */
public class C {
    private D d = new D();
    private B b = new B();
    private A a;

    public C(A a) {
        this.a = a;
    }

    public void doC() {
        d.doD(a);
        b.doB();
    }
}

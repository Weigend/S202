package com.example3.sub1;

import com.example3.sub2.C;

/**
 * example3 package-cycle demo.
 * A depends on B (2 edges: field + call) and on C (sub1->sub2, completing the cycle).
 * Cycle: A -> C -> D -> A
 */
public class A {
    private B b = new B();
    private C c = new C(this);

    public void doA() {
        b.doB();
        c.doC();
    }
}

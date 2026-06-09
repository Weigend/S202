package com.example3.sub2;

import com.example3.sub1.A;

/**
 * example3 package-cycle demo.
 * D depends on A (2 edges: INSTANTIATES + CALLS), creating sub2->sub1 dependency.
 */
public class D {
    private A a = new A();

    public void doD() {
        a.doA();
    }
}

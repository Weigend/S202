package com.example3.sub1;

/**
 * example3 package-cycle demo.
 * A depends on B (2 edges: INSTANTIATES + CALLS), establishing A above B.
 */
public class A {
    private B b = new B();

    public void doA() {
        b.doB();
    }
}

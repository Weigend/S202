package com.example3.sub1;

import com.example3.sub2.C;

/**
 * example3 package-cycle demo.
 * B depends on C (sub1->sub2), completing the package cycle sub1 ↔ sub2.
 */
public class B {
    private C c = new C();

    public void doB() {
        c.doC();
    }
}

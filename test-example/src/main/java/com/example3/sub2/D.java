package com.example3.sub2;

import com.example3.sub1.A;

/**
 * example3 package-cycle demo.
 * D depends on A (sub2->sub1), closing the class cycle A->C->D->A.
 */
public class D {
    public void doD(A a) {
        a.doA();
    }
}

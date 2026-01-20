package com.example2;

import com.example.B;
import com.example1.X;

/**
 * Cross-package dependency class.
 * E depends on:
 * - com.example2.A (same package)
 * - com.example.B (different package)
 * - com.example1.X (different package)
 * 
 * This demonstrates how packages should be leveled based on their internal dependencies.
 */
public class E {
    private A example2A = new A();
    private B exampleB = new B();
    private X example1X = new X();
    
    public void complexMethod() {
        example2A.methodA();
        exampleB.getAInfo();
        example1X.doSomething();
    }
}

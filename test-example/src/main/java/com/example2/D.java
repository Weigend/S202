package com.example2;

/**
 * Base class in diamond dependency pattern (example2).
 * D <- B <- A
 * D <- C <- A
 */
public class D {
    public void baseMethod() {
        System.out.println("D.baseMethod()");
    }
}

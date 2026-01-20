package com.example2;

/**
 * Middle class in diamond dependency pattern.
 * Depends on D (like B).
 */
public class C {
    private D d = new D();
    
    public void methodC() {
        d.baseMethod();
    }
}

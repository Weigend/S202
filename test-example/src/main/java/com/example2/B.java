package com.example2;

/**
 * Middle class in diamond dependency pattern.
 * Depends on D.
 */
public class B {
    private D d = new D();
    
    public void methodB() {
        d.baseMethod();
    }
}

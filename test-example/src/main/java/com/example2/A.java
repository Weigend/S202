package com.example2;

/**
 * Class A - Depends on B and C
 * Part of diamond dependency pattern:
 *   A -> B -> D
 *   A -> C -> D
 */
public class A {
    private B b = new B();
    private C c = new C();
    
    public String getName() {
        return "Class A";
    }
    
    public String getBInfo() {
        return b.getName();
    }
    
    public String getCInfo() {
        return c.getName();
    }
}

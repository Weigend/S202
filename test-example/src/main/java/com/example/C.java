package com.example;

/**
 * Class C - Depends on B
 */
public class C {
    private B b = new B();
    
    public String getName() {
        return "Class C";
    }
    
    public String getBInfo() {
        return b.getName();
    }
}

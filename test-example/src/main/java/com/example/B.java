package com.example;

/**
 * Class B - Depends on A
 */
public class B {
    private A a = new A();
    
    public String getName() {
        return "Class B";
    }
    
    public String getAInfo() {
        return a.getName();
    }
}

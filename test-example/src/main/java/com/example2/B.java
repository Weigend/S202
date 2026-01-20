package com.example2;

/**
 * Class B - Depends on D
 * Part of diamond dependency: A -> B -> D
 */
public class B {
    private D d = new D();
    
    public String getName() {
        return "Class B";
    }
    
    public String getDInfo() {
        return d.getName();
    }
}

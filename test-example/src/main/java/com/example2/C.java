package com.example2;

/**
 * Class C - Depends on D
 * Part of diamond dependency: A -> C -> D
 */
public class C {
    private D d = new D();
    
    public String getName() {
        return "Class C";
    }
    
    public String getDInfo() {
        return d.getName();
    }
}

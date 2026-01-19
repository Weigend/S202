package com.example.a;

import com.example.c.C;
import com.example.b.B;

public class A {
    private B b = new B();
    
    public A() {}
    public void run() { System.out.println("A"); }
}

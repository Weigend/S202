package com.example.a;

import com.example.b.B;

/**
 * Class A in package a.
 * A depends on B.
 */
public class A {
    private B b;

    public A() {
        this.b = new B();
    }

    public B getB() {
        return b;
    }

    public void doSomething() {
        b.process();
    }
}

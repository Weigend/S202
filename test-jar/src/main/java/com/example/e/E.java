package com.example.e;

import com.example.a.A;

/**
 * Class E in package e.
 * E depends on A (creating a cycle: A -> B -> E -> A).
 */
public class E {
    private A a;

    public E() {
        this.a = new A();
    }

    public void run() {
        a.doSomething();
    }

    public A getA() {
        return a;
    }
}

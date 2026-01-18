package com.example.b;

import com.example.c.C;
import com.example.e.E;

/**
 * Class B in package b.
 * B depends on C and E.
 */
public class B {
    private C c;
    private E e;

    public B() {
        this.c = new C();
        this.e = new E();
    }

    public void process() {
        c.execute();
        e.run();
    }

    public C getC() {
        return c;
    }

    public E getE() {
        return e;
    }
}

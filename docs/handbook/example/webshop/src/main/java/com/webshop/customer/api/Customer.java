package com.webshop.customer.api;

import com.webshop.common.AuditLog;
import com.webshop.common.Identifier;

/** A customer of the shop. */
public class Customer {

    private final Identifier id;
    private String name;

    public Customer(Identifier id, String name) {
        this.id = id;
        this.name = name;
    }

    public Identifier id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        AuditLog.record(this, "renamed to " + newName);
        name = newName;
    }
}

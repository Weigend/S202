package com.webshop.customer.impl;

class NamePolicy {

    void check(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a customer needs a name");
        }
    }
}

package com.webshop.common;

/** The key of a business object. */
public record Identifier(String value) {

    public boolean isBlank() {
        return value == null || value.isBlank();
    }
}

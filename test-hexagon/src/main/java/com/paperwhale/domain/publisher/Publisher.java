package com.paperwhale.domain.publisher;

/**
 * A book publisher. Publishers are the innermost concept of the Paper Whale
 * domain: books reference publishers, never the other way around.
 */
public record Publisher(String name, String city, int foundedYear) {

    public Publisher {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a publisher needs a name");
        }
    }

    public String letterhead() {
        return name + ", " + city + " (est. " + foundedYear + ")";
    }
}

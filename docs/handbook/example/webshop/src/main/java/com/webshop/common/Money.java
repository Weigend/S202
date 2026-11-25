package com.webshop.common;

/** An amount in cents. */
public record Money(long cents) {

    public static final Money ZERO = new Money(0);

    public Money plus(Money other) {
        return new Money(cents + other.cents);
    }

    public Money times(int factor) {
        return new Money(cents * factor);
    }

    public boolean isPositive() {
        return cents > 0;
    }
}

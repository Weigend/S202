package com.paperwhale.domain.book;

/**
 * Value object for an ISBN-13. The Paper Whale is forgiving about hyphens but
 * strict about length.
 */
public record Isbn(String value) {

    public Isbn {
        String digits = value == null ? "" : value.replace("-", "");
        if (digits.length() != 13 || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("not an ISBN-13: " + value);
        }
    }

    public String compact() {
        return value.replace("-", "");
    }
}

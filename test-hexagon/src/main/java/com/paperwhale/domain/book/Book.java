package com.paperwhale.domain.book;

import com.paperwhale.domain.publisher.Imprint;

/**
 * A book in the Paper Whale catalog. Prices are kept in cents because the
 * owner once lost 30 cents to floating point and never forgave it.
 */
public record Book(Isbn isbn, String title, String author, Genre genre, Imprint imprint, int priceCents) {

    public String shelfLabel() {
        return title + " — " + author;
    }

    public String priceTag() {
        return "%d.%02d EUR".formatted(priceCents / 100, priceCents % 100);
    }
}

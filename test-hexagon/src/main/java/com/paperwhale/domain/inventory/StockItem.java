package com.paperwhale.domain.inventory;

import com.paperwhale.domain.book.Isbn;

/**
 * The stock situation of one title: a few copies on the shelf for browsing
 * customers, the rest in the backroom between the kettle and the whale poster.
 */
public final class StockItem {

    private final Isbn isbn;
    private int copiesOnShelf;
    private int copiesInBackroom;

    public StockItem(Isbn isbn, int copiesOnShelf, int copiesInBackroom) {
        this.isbn = isbn;
        this.copiesOnShelf = copiesOnShelf;
        this.copiesInBackroom = copiesInBackroom;
    }

    public Isbn isbn() {
        return isbn;
    }

    public int copiesOnShelf() {
        return copiesOnShelf;
    }

    public int totalCopies() {
        return copiesOnShelf + copiesInBackroom;
    }

    /** Sells one copy from the shelf, refilling it from the backroom if possible. */
    public void sellOne() {
        if (copiesOnShelf <= 0) {
            throw new IllegalStateException("shelf is empty for " + isbn.value());
        }
        copiesOnShelf--;
        if (copiesOnShelf == 0 && copiesInBackroom > 0) {
            copiesInBackroom--;
            copiesOnShelf++;
        }
    }

    /** A delivery arrived: everything goes into the backroom first. */
    public void receive(int copies) {
        copiesInBackroom += copies;
    }
}

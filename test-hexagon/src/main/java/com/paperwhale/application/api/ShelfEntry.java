package com.paperwhale.application.api;

import com.paperwhale.domain.book.Book;

/** One row of the shelf view: a book and how many copies are on display. */
public record ShelfEntry(Book book, int copiesOnShelf) {
}

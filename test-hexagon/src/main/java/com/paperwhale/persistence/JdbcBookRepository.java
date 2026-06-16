package com.paperwhale.persistence;

import com.paperwhale.application.spi.BookRepository;
import com.paperwhale.domain.book.Book;
import com.paperwhale.domain.book.Genre;
import com.paperwhale.domain.book.Isbn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter: implements the BookRepository SPI. Simulates the table
 * BOOKS(isbn PRIMARY KEY, title, author, genre, imprint, price_cents) with an
 * in-memory map so the demo runs without a database server.
 */
public final class JdbcBookRepository implements BookRepository {

    private final Map<String, Book> booksTable = new LinkedHashMap<>();

    @Override
    public void save(Book book) {
        // MERGE INTO books ... VALUES (?, ?, ?, ?, ?, ?)
        booksTable.put(book.isbn().compact(), book);
    }

    @Override
    public Optional<Book> findByIsbn(Isbn isbn) {
        // SELECT * FROM books WHERE isbn = ?
        return Optional.ofNullable(booksTable.get(isbn.compact()));
    }

    @Override
    public List<Book> findByGenre(Genre genre) {
        // SELECT * FROM books WHERE genre = ? ORDER BY title
        return booksTable.values().stream()
                .filter(book -> book.genre() == genre)
                .sorted((a, b) -> a.title().compareTo(b.title()))
                .toList();
    }
}

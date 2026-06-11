package com.paperwhale.application.spi;

import com.paperwhale.domain.book.Book;
import com.paperwhale.domain.book.Genre;
import com.paperwhale.domain.book.Isbn;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port (SPI): the application core defines how it wants to read and
 * write books; the persistence adapter provides the implementation.
 */
public interface BookRepository {

    void save(Book book);

    Optional<Book> findByIsbn(Isbn isbn);

    List<Book> findByGenre(Genre genre);
}

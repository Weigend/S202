package com.paperwhale.application.service;

import com.paperwhale.application.api.BrowseCatalogUseCase;
import com.paperwhale.application.api.ShelfEntry;
import com.paperwhale.application.spi.BookRepository;
import com.paperwhale.application.spi.InventoryStore;
import com.paperwhale.domain.book.Genre;

import java.util.List;

/** Implements browsing: join the catalog with the shelf situation. */
public final class CatalogService implements BrowseCatalogUseCase {

    private final BookRepository bookRepository;
    private final InventoryStore inventoryStore;

    public CatalogService(BookRepository bookRepository, InventoryStore inventoryStore) {
        this.bookRepository = bookRepository;
        this.inventoryStore = inventoryStore;
    }

    @Override
    public List<ShelfEntry> browseShelf(Genre genre) {
        return bookRepository.findByGenre(genre).stream()
                .map(book -> new ShelfEntry(
                        book,
                        inventoryStore.byIsbn(book.isbn())
                                .map(item -> item.copiesOnShelf())
                                .orElse(0)))
                .toList();
    }
}

package com.paperwhale.application.api;

import com.paperwhale.domain.book.Genre;

import java.util.List;

/** Inbound port: what a customer (via UI or REST) may ask the store. */
public interface BrowseCatalogUseCase {

    List<ShelfEntry> browseShelf(Genre genre);
}

package com.paperwhale.rest;

import com.paperwhale.application.api.BrowseCatalogUseCase;
import com.paperwhale.domain.book.Genre;
import com.paperwhale.platform.JsonCodec;

/**
 * Driving adapter: simulates GET /catalog?genre=... by calling the inbound
 * port and rendering the result as JSON.
 */
public final class CatalogResource {

    private final BrowseCatalogUseCase browseCatalog;
    private final JsonCodec json = new JsonCodec();

    public CatalogResource(BrowseCatalogUseCase browseCatalog) {
        this.browseCatalog = browseCatalog;
    }

    /** GET /catalog?genre={genre} */
    public String getCatalog(String genre) {
        return json.toJson(browseCatalog.browseShelf(Genre.valueOf(genre.toUpperCase())));
    }
}

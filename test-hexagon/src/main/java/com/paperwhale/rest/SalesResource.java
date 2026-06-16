package com.paperwhale.rest;

import com.paperwhale.application.api.SellBookUseCase;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.platform.JsonCodec;

/**
 * Driving adapter: simulates POST /sales by calling the inbound port and
 * rendering the receipt as JSON.
 */
public final class SalesResource {

    private final SellBookUseCase sellBook;
    private final JsonCodec json = new JsonCodec();

    public SalesResource(SellBookUseCase sellBook) {
        this.sellBook = sellBook;
    }

    /** POST /sales {"isbn": "..."} */
    public String postSale(String isbn) {
        return json.toJson(sellBook.sellOne(new Isbn(isbn)));
    }
}

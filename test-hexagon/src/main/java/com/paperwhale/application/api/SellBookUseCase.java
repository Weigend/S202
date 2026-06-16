package com.paperwhale.application.api;

import com.paperwhale.domain.book.Isbn;

/** Inbound port: sell one copy over the counter. */
public interface SellBookUseCase {

    SaleReceipt sellOne(Isbn isbn);
}

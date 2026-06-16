package com.paperwhale.application.api;

import com.paperwhale.domain.book.Isbn;

/** Inbound port: order fresh copies from the publisher. */
public interface RestockUseCase {

    TrackingInfo restock(Isbn isbn);
}

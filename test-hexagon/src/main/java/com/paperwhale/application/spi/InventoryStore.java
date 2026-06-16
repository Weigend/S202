package com.paperwhale.application.spi;

import com.paperwhale.domain.book.Isbn;
import com.paperwhale.domain.inventory.StockItem;

import java.util.Optional;

/** Outbound port (SPI): durable stock bookkeeping. */
public interface InventoryStore {

    void put(StockItem item);

    Optional<StockItem> byIsbn(Isbn isbn);
}

package com.paperwhale.persistence;

import com.paperwhale.application.spi.InventoryStore;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.domain.inventory.StockItem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter: implements the InventoryStore SPI. Simulates the table
 * STOCK(isbn PRIMARY KEY, copies_on_shelf, copies_in_backroom).
 */
public final class JdbcInventoryStore implements InventoryStore {

    private final Map<String, StockItem> stockTable = new LinkedHashMap<>();

    @Override
    public void put(StockItem item) {
        // MERGE INTO stock ... VALUES (?, ?, ?)
        stockTable.put(item.isbn().compact(), item);
    }

    @Override
    public Optional<StockItem> byIsbn(Isbn isbn) {
        // SELECT * FROM stock WHERE isbn = ?
        return Optional.ofNullable(stockTable.get(isbn.compact()));
    }
}

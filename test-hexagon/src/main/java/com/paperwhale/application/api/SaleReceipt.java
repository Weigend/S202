package com.paperwhale.application.api;

import com.paperwhale.domain.book.Book;

import java.util.Optional;

/**
 * Result of a sale. If the sale drained the stock below the reorder
 * threshold, the receipt carries the tracking info of the restock shipment
 * that was triggered on the spot.
 */
public record SaleReceipt(Book book, int copiesLeft, Optional<TrackingInfo> restockTriggered) {
}

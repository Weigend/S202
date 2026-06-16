package com.paperwhale.application.service;

import com.paperwhale.application.api.RestockUseCase;
import com.paperwhale.application.api.SaleReceipt;
import com.paperwhale.application.api.SellBookUseCase;
import com.paperwhale.application.api.TrackingInfo;
import com.paperwhale.application.spi.BookRepository;
import com.paperwhale.application.spi.CustomerNotifier;
import com.paperwhale.application.spi.InventoryStore;
import com.paperwhale.domain.book.Book;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.domain.inventory.ReorderPolicy;
import com.paperwhale.domain.inventory.StockItem;

import java.util.Optional;

/**
 * Implements selling. When a sale drains the stock below the reorder
 * threshold, the restock use case is triggered immediately — the Paper Whale
 * never lets a shelf run dry.
 */
public final class SalesService implements SellBookUseCase {

    private final BookRepository bookRepository;
    private final InventoryStore inventoryStore;
    private final CustomerNotifier notifier;
    private final RestockUseCase restockUseCase;
    private final ReorderPolicy reorderPolicy;

    public SalesService(BookRepository bookRepository,
                        InventoryStore inventoryStore,
                        CustomerNotifier notifier,
                        RestockUseCase restockUseCase,
                        ReorderPolicy reorderPolicy) {
        this.bookRepository = bookRepository;
        this.inventoryStore = inventoryStore;
        this.notifier = notifier;
        this.restockUseCase = restockUseCase;
        this.reorderPolicy = reorderPolicy;
    }

    @Override
    public SaleReceipt sellOne(Isbn isbn) {
        Book book = bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new IllegalArgumentException("not in catalog: " + isbn.value()));
        StockItem item = inventoryStore.byIsbn(isbn)
                .orElseThrow(() -> new IllegalStateException("no stock record for " + isbn.value()));

        item.sellOne();
        inventoryStore.put(item);
        notifier.notifyCustomer("Enjoy \"" + book.title() + "\"! Come back soon.");

        Optional<TrackingInfo> restock = Optional.empty();
        if (reorderPolicy.needsRestock(item)) {
            restock = Optional.of(restockUseCase.restock(isbn));
        }
        return new SaleReceipt(book, item.totalCopies(), restock);
    }
}

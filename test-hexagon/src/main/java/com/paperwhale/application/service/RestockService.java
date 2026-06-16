package com.paperwhale.application.service;

import com.paperwhale.application.api.RestockUseCase;
import com.paperwhale.application.api.TrackingInfo;
import com.paperwhale.application.spi.BookRepository;
import com.paperwhale.application.spi.InventoryStore;
import com.paperwhale.application.spi.PublisherDirectory;
import com.paperwhale.application.spi.ShipmentCarrier;
import com.paperwhale.domain.book.Book;
import com.paperwhale.domain.book.Isbn;
import com.paperwhale.domain.inventory.ReorderPolicy;
import com.paperwhale.domain.logistics.Shipment;
import com.paperwhale.domain.logistics.TrackingCode;

/**
 * Implements restocking: look up the publisher, pack a shipment, hand it to
 * the carrier, and book the copies into the backroom on arrival (the demo
 * carrier is optimistic and fast).
 */
public final class RestockService implements RestockUseCase {

    private final BookRepository bookRepository;
    private final InventoryStore inventoryStore;
    private final PublisherDirectory publisherDirectory;
    private final ShipmentCarrier carrier;
    private final ReorderPolicy reorderPolicy;

    public RestockService(BookRepository bookRepository,
                          InventoryStore inventoryStore,
                          PublisherDirectory publisherDirectory,
                          ShipmentCarrier carrier,
                          ReorderPolicy reorderPolicy) {
        this.bookRepository = bookRepository;
        this.inventoryStore = inventoryStore;
        this.publisherDirectory = publisherDirectory;
        this.carrier = carrier;
        this.reorderPolicy = reorderPolicy;
    }

    @Override
    public TrackingInfo restock(Isbn isbn) {
        Book book = bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new IllegalArgumentException("not in catalog: " + isbn.value()));
        String from = publisherDirectory.publisherOf(book.imprint().label())
                .map(publisher -> publisher.letterhead())
                .orElse("an undisclosed warehouse");

        Shipment shipment = new Shipment(new TrackingCode("PENDING"));
        shipment.add(isbn, reorderPolicy.batchSize());
        TrackingCode code = carrier.pickUp(shipment);

        inventoryStore.byIsbn(isbn).ifPresent(item -> {
            item.receive(reorderPolicy.batchSize());
            inventoryStore.put(item);
        });
        return new TrackingInfo(code, carrier.statusOf(code),
                reorderPolicy.batchSize() + "x \"" + book.title() + "\" on its way from " + from);
    }
}

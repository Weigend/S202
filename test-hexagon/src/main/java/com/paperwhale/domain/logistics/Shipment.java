package com.paperwhale.domain.logistics;

import com.paperwhale.domain.book.Isbn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A restock shipment on its way to the store: which titles, how many copies,
 * and how far the seagulls have carried it.
 */
public final class Shipment {

    private final TrackingCode trackingCode;
    private final Map<Isbn, Integer> manifest = new LinkedHashMap<>();
    private ShipmentStatus status = ShipmentStatus.PACKED;

    public Shipment(TrackingCode trackingCode) {
        this.trackingCode = trackingCode;
    }

    public void add(Isbn isbn, int copies) {
        manifest.merge(isbn, copies, Integer::sum);
    }

    public TrackingCode trackingCode() {
        return trackingCode;
    }

    public Map<Isbn, Integer> manifest() {
        return Map.copyOf(manifest);
    }

    public ShipmentStatus status() {
        return status;
    }

    public void advance() {
        status = status.advance();
    }

    public int totalCopies() {
        return manifest.values().stream().mapToInt(Integer::intValue).sum();
    }
}

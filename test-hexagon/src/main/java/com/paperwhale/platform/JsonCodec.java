package com.paperwhale.platform;

import com.paperwhale.application.api.SaleReceipt;
import com.paperwhale.application.api.ShelfEntry;
import com.paperwhale.application.api.TrackingInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Hand-rolled JSON for the REST adapter. Deliberately concrete: it knows the
 * use-case result types of the application API and nothing else.
 */
public final class JsonCodec {

    public String toJson(ShelfEntry entry) {
        return "{\"isbn\": \"%s\", \"title\": \"%s\", \"author\": \"%s\", \"price\": \"%s\", \"onShelf\": %d}"
                .formatted(
                        entry.book().isbn().value(),
                        entry.book().title(),
                        entry.book().author(),
                        entry.book().priceTag(),
                        entry.copiesOnShelf());
    }

    public String toJson(List<ShelfEntry> entries) {
        return entries.stream()
                .map(this::toJson)
                .collect(Collectors.joining(",\n  ", "[\n  ", "\n]"));
    }

    public String toJson(SaleReceipt receipt) {
        return "{\"sold\": \"%s\", \"copiesLeft\": %d, \"restock\": %s}"
                .formatted(
                        receipt.book().title(),
                        receipt.copiesLeft(),
                        receipt.restockTriggered().map(this::toJson).orElse("null"));
    }

    public String toJson(TrackingInfo info) {
        return "{\"trackingCode\": \"%s\", \"status\": \"%s\", \"summary\": \"%s\"}"
                .formatted(info.trackingCode().value(), info.status(), info.summary());
    }
}

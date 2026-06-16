package com.paperwhale.persistence;

import com.paperwhale.application.spi.PublisherDirectory;
import com.paperwhale.domain.publisher.Imprint;
import com.paperwhale.domain.publisher.Publisher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter: implements the PublisherDirectory SPI. Simulates the table
 * IMPRINTS(label PRIMARY KEY, publisher_name, publisher_city, founded_year).
 */
public final class JdbcPublisherDirectory implements PublisherDirectory {

    private final Map<String, Imprint> imprintsTable = new LinkedHashMap<>();

    @Override
    public void register(Imprint imprint) {
        // INSERT INTO imprints ... VALUES (?, ?, ?, ?)
        imprintsTable.put(imprint.label(), imprint);
    }

    @Override
    public Optional<Publisher> publisherOf(String imprintLabel) {
        // SELECT publisher_* FROM imprints WHERE label = ?
        return Optional.ofNullable(imprintsTable.get(imprintLabel)).map(Imprint::publisher);
    }
}

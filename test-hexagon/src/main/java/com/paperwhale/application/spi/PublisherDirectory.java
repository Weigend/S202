package com.paperwhale.application.spi;

import com.paperwhale.domain.publisher.Imprint;
import com.paperwhale.domain.publisher.Publisher;

import java.util.Optional;

/** Outbound port (SPI): who publishes what, and how to reach them. */
public interface PublisherDirectory {

    void register(Imprint imprint);

    Optional<Publisher> publisherOf(String imprintLabel);
}

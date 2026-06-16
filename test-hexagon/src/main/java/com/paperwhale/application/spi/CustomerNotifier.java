package com.paperwhale.application.spi;

/**
 * Outbound port (SPI): how the store talks to its customers. The UI adapter
 * implements this with a friendly console bell.
 */
public interface CustomerNotifier {

    void notifyCustomer(String message);
}

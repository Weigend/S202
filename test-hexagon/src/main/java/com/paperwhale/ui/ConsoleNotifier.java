package com.paperwhale.ui;

import com.paperwhale.application.spi.CustomerNotifier;
import com.paperwhale.platform.TextTable;

/**
 * Driven adapter on the UI side: the application core defines WHEN customers
 * are notified (CustomerNotifier SPI), this class decides HOW — with a little
 * bell on the counter.
 */
public final class ConsoleNotifier implements CustomerNotifier {

    private final TextTable textTable = new TextTable();

    @Override
    public void notifyCustomer(String message) {
        System.out.println(textTable.banner("🔔 " + message));
    }
}

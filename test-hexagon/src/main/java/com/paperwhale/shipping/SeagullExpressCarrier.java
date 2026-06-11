package com.paperwhale.shipping;

import com.paperwhale.application.spi.ShipmentCarrier;
import com.paperwhale.domain.logistics.Shipment;
import com.paperwhale.domain.logistics.ShipmentStatus;
import com.paperwhale.domain.logistics.TrackingCode;
import com.paperwhale.platform.TextTable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Driven adapter: implements the ShipmentCarrier SPI. Seagull Express flies
 * books across the bay strapped to surprisingly disciplined seagulls. Every
 * status request advances the flock — they are very fast birds.
 */
public final class SeagullExpressCarrier implements ShipmentCarrier {

    private final Map<String, Shipment> activeFlights = new LinkedHashMap<>();
    private final TextTable textTable = new TextTable();
    private int nextFlightNumber = 41;

    @Override
    public TrackingCode pickUp(Shipment shipment) {
        TrackingCode code = new TrackingCode("SGE-%05d".formatted(++nextFlightNumber));
        activeFlights.put(code.value(), shipment);
        System.out.println(textTable.banner(
                "SEAGULL EXPRESS: flight " + code.value() + " — "
                        + shipment.totalCopies() + " books cleared for take-off"));
        return code;
    }

    @Override
    public ShipmentStatus statusOf(TrackingCode trackingCode) {
        Shipment shipment = activeFlights.get(trackingCode.value());
        if (shipment == null) {
            return ShipmentStatus.DELIVERED;
        }
        shipment.advance();
        return shipment.status();
    }
}

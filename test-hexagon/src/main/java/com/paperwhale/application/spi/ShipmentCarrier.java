package com.paperwhale.application.spi;

import com.paperwhale.domain.logistics.Shipment;
import com.paperwhale.domain.logistics.ShipmentStatus;
import com.paperwhale.domain.logistics.TrackingCode;

/**
 * Outbound port (SPI): the core decides WHAT to ship, a carrier adapter
 * decides HOW. Implemented by Seagull Express — do not ask about the parrots.
 */
public interface ShipmentCarrier {

    TrackingCode pickUp(Shipment shipment);

    ShipmentStatus statusOf(TrackingCode trackingCode);
}

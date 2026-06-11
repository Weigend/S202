package com.paperwhale.application.service;

import com.paperwhale.application.api.TrackShipmentUseCase;
import com.paperwhale.application.api.TrackingInfo;
import com.paperwhale.application.spi.ShipmentCarrier;
import com.paperwhale.domain.logistics.ShipmentStatus;
import com.paperwhale.domain.logistics.TrackingCode;

/** Implements tracking: ask the carrier, translate for humans. */
public final class TrackingService implements TrackShipmentUseCase {

    private final ShipmentCarrier carrier;

    public TrackingService(ShipmentCarrier carrier) {
        this.carrier = carrier;
    }

    @Override
    public TrackingInfo track(TrackingCode trackingCode) {
        ShipmentStatus status = carrier.statusOf(trackingCode);
        String summary = switch (status) {
            case PACKED -> "Packed and waiting for the next seagull.";
            case IN_TRANSIT -> "Airborne! The flock is on its way.";
            case DELIVERED -> "Delivered. Happy reading!";
        };
        return new TrackingInfo(trackingCode, status, summary);
    }
}

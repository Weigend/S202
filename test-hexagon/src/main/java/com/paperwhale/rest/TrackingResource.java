package com.paperwhale.rest;

import com.paperwhale.application.api.TrackShipmentUseCase;
import com.paperwhale.domain.logistics.TrackingCode;
import com.paperwhale.platform.JsonCodec;

/**
 * Driving adapter: simulates GET /shipments/{trackingCode} by calling the
 * inbound port and rendering the status as JSON.
 */
public final class TrackingResource {

    private final TrackShipmentUseCase trackShipment;
    private final JsonCodec json = new JsonCodec();

    public TrackingResource(TrackShipmentUseCase trackShipment) {
        this.trackShipment = trackShipment;
    }

    /** GET /shipments/{trackingCode} */
    public String getShipment(String trackingCode) {
        return json.toJson(trackShipment.track(new TrackingCode(trackingCode)));
    }
}

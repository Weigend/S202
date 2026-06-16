package com.paperwhale.application.api;

import com.paperwhale.domain.logistics.TrackingCode;

/** Inbound port: where are my books? */
public interface TrackShipmentUseCase {

    TrackingInfo track(TrackingCode trackingCode);
}

package com.paperwhale.application.api;

import com.paperwhale.domain.logistics.ShipmentStatus;
import com.paperwhale.domain.logistics.TrackingCode;

/** Where a shipment is right now, in customer-friendly words. */
public record TrackingInfo(TrackingCode trackingCode, ShipmentStatus status, String summary) {
}

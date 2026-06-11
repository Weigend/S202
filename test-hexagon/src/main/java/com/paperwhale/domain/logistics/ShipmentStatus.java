package com.paperwhale.domain.logistics;

/** The life of a shipment, from packing table to doormat. */
public enum ShipmentStatus {
    PACKED,
    IN_TRANSIT,
    DELIVERED;

    public ShipmentStatus advance() {
        return switch (this) {
            case PACKED -> IN_TRANSIT;
            case IN_TRANSIT, DELIVERED -> DELIVERED;
        };
    }
}

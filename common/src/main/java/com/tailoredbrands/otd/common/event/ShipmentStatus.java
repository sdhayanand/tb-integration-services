package com.tailoredbrands.otd.common.event;

/** Carrier-neutral shipment status (ARCHITECTURE §3.3). */
public enum ShipmentStatus {
    LABEL_CREATED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION
}

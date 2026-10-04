package com.tailoredbrands.otd.common.event;

import java.time.Instant;

/**
 * Event published on topic {@code shipments-v1} (ARCHITECTURE §3.3).
 *
 * @param carrier  {@code UPS} / {@code FEDEX}
 * @param location free-text carrier location, e.g. {@code Oakland, CA}
 */
public record ShipmentEvent(
        String eventId,
        String eventType,
        Instant eventTime,
        String schemaVersion,
        String source,
        String correlationId,
        String orderId,
        String trackingNumber,
        String carrier,
        ShipmentStatus status,
        Instant statusTime,
        String location) {

    public static final String SCHEMA_VERSION = "1";
    public static final String EVENT_TYPE_SHIPMENT_UPDATED = "SHIPMENT_UPDATED";
    public static final String SOURCE_SHIPMENT_WEBHOOK = "SHIPMENT_WEBHOOK";
}

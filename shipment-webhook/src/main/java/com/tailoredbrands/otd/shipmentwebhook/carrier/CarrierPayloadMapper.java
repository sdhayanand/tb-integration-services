package com.tailoredbrands.otd.shipmentwebhook.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.event.ShipmentStatus;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidPayloadException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps one carrier's native webhook JSON to the canonical {@link ShipmentEvent} (ARCHITECTURE §3.3).
 */
public interface CarrierPayloadMapper {

    /** Carrier code as used in the URL and in {@code ShipmentEvent.carrier}: {@code UPS}, {@code FEDEX}. */
    String carrier();

    ShipmentEvent map(JsonNode payload, String correlationId, Clock clock);

    /** Helper for implementations: builds the envelope around the mapped fields. */
    static ShipmentEvent event(String carrier, String correlationId, Clock clock, String orderId, String trackingNumber,
                               ShipmentStatus status, Instant statusTime, String location) {
        return new ShipmentEvent(UUID.randomUUID().toString(), ShipmentEvent.EVENT_TYPE_SHIPMENT_UPDATED,
                Instant.now(clock), ShipmentEvent.SCHEMA_VERSION, ShipmentEvent.SOURCE_SHIPMENT_WEBHOOK, correlationId,
                orderId, trackingNumber, carrier, status, statusTime == null ? Instant.now(clock) : statusTime, location);
    }

    static String requiredText(JsonNode node, String path) {
        String value = optionalText(node, path);
        if (value == null) {
            throw new InvalidPayloadException("missing required field '" + path + "'");
        }
        return value;
    }

    /** Dotted path lookup, {@code null} when absent/blank. */
    static String optionalText(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        String text = current.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }
}

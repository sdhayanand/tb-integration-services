package com.tailoredbrands.otd.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope published on topic {@code orders-v1} (ARCHITECTURE §3.1).
 *
 * @param eventId         UUID set by the publisher (idempotency key for consumers)
 * @param eventType       created / updated / cancelled
 * @param eventTime       RFC-3339 instant
 * @param schemaVersion   {@code "1"}
 * @param source          which component produced the event
 * @param correlationId   end-to-end correlation id (from {@code X-Correlation-Id})
 * @param legacyMessageId JMS message id when bridged from EMS/MQ; otherwise {@code null}
 * @param order           the order
 */
public record OrderEvent(
        String eventId,
        OrderEventType eventType,
        Instant eventTime,
        String schemaVersion,
        EventSource source,
        String correlationId,
        String legacyMessageId,
        Order order) {

    public static final String SCHEMA_VERSION = "1";

    /** Convenience factory used by publishers: new UUID, now, schema version 1, no legacy id. */
    public static OrderEvent of(OrderEventType eventType, EventSource source, String correlationId, Order order) {
        return new OrderEvent(UUID.randomUUID().toString(), eventType, Instant.now(), SCHEMA_VERSION,
                source, correlationId, null, order);
    }
}

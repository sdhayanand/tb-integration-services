package com.tailoredbrands.otd.common.event;

import java.time.Instant;
import java.util.List;

/**
 * Event published on topic {@code inventory-v1} (ARCHITECTURE §3.2).
 */
public record InventoryEvent(
        String eventId,
        InventoryEventType eventType,
        Instant eventTime,
        String schemaVersion,
        String source,
        String correlationId,
        String orderId,
        String storeId,
        List<InventoryLine> lines) {

    public static final String SCHEMA_VERSION = "1";
    public static final String SOURCE_INVENTORY_SERVICE = "INVENTORY_SERVICE";

    public InventoryEvent {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}

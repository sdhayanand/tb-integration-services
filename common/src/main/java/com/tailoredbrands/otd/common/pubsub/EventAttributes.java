package com.tailoredbrands.otd.common.pubsub;

import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.ShipmentEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the standard Pub/Sub attribute map every publisher sets (ARCHITECTURE §3.1):
 * {@code eventType, schemaVersion, source, storeId, correlationId} and {@code legacyMessageId} when present.
 * Attributes with a {@code null} value are omitted (Pub/Sub attribute values cannot be null).
 */
public final class EventAttributes {

    public static final String EVENT_TYPE = "eventType";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String SOURCE = "source";
    public static final String STORE_ID = "storeId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String LEGACY_MESSAGE_ID = "legacyMessageId";
    public static final String EVENT_ID = "eventId";
    public static final String ORDER_ID = "orderId";
    public static final String CARRIER = "carrier";

    private EventAttributes() {
    }

    public static Map<String, String> of(String eventType, String schemaVersion, String source, String storeId,
                                         String correlationId, String legacyMessageId) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, EVENT_TYPE, eventType);
        put(attributes, SCHEMA_VERSION, schemaVersion);
        put(attributes, SOURCE, source);
        put(attributes, STORE_ID, storeId);
        put(attributes, CORRELATION_ID, correlationId);
        put(attributes, LEGACY_MESSAGE_ID, legacyMessageId);
        return Collections.unmodifiableMap(attributes);
    }

    public static Map<String, String> forOrderEvent(OrderEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>(of(
                name(event.eventType()), event.schemaVersion(), name(event.source()),
                event.order() == null ? null : event.order().storeId(),
                event.correlationId(), event.legacyMessageId()));
        put(attributes, EVENT_ID, event.eventId());
        put(attributes, ORDER_ID, event.order() == null ? null : event.order().orderId());
        return Collections.unmodifiableMap(attributes);
    }

    public static Map<String, String> forInventoryEvent(InventoryEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>(of(
                name(event.eventType()), event.schemaVersion(), event.source(), event.storeId(),
                event.correlationId(), null));
        put(attributes, EVENT_ID, event.eventId());
        put(attributes, ORDER_ID, event.orderId());
        return Collections.unmodifiableMap(attributes);
    }

    public static Map<String, String> forShipmentEvent(ShipmentEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>(of(
                event.eventType(), event.schemaVersion(), event.source(), null,
                event.correlationId(), null));
        put(attributes, EVENT_ID, event.eventId());
        put(attributes, ORDER_ID, event.orderId());
        put(attributes, CARRIER, event.carrier());
        return Collections.unmodifiableMap(attributes);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}

package com.tailoredbrands.otd.common.pubsub;

import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderEventType;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.event.ShipmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventAttributesTest {

    private static Order order() {
        return new Order("ORD-2026-000007", OrderType.RETAIL, Channel.STORE, "0412", null,
                Instant.parse("2026-10-03T22:14:00Z"), null, "USD", new BigDecimal("10.00"),
                List.of(new OrderLine(1, "SKU", 1, new BigDecimal("10.00"), FulfillmentType.STORE_PICKUP, null)),
                null, null);
    }

    @Test
    void standardAttributesForOrderEvent() {
        OrderEvent event = new OrderEvent("evt-1", OrderEventType.ORDER_CREATED, Instant.now(), "1",
                EventSource.ORDER_INTAKE_API, "corr-1", null, order());

        Map<String, String> attributes = EventAttributes.forOrderEvent(event);

        assertThat(attributes)
                .containsEntry("eventType", "ORDER_CREATED")
                .containsEntry("schemaVersion", "1")
                .containsEntry("source", "ORDER_INTAKE_API")
                .containsEntry("storeId", "0412")
                .containsEntry("correlationId", "corr-1")
                .containsEntry("eventId", "evt-1")
                .containsEntry("orderId", "ORD-2026-000007")
                .doesNotContainKey("legacyMessageId");
        assertThat(attributes.values()).doesNotContainNull();
    }

    @Test
    void legacyMessageIdIncludedWhenPresent() {
        OrderEvent event = new OrderEvent("evt-2", OrderEventType.ORDER_CREATED, Instant.now(), "1",
                EventSource.TIBCO_EMS_BRIDGE, "corr-2", "ID:EMS-SERVER.1A2B3C", order());

        assertThat(EventAttributes.forOrderEvent(event)).containsEntry("legacyMessageId", "ID:EMS-SERVER.1A2B3C");
    }

    @Test
    void mapIsUnmodifiable() {
        Map<String, String> attributes = EventAttributes.of("ORDER_CREATED", "1", "REPLAY", "0412", "c", null);
        assertThatThrownBy(() -> attributes.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void inventoryAndShipmentAttributes() {
        InventoryEvent inv = new InventoryEvent("i1", InventoryEventType.INVENTORY_BACKORDERED, Instant.now(), "1",
                InventoryEvent.SOURCE_INVENTORY_SERVICE, "c3", "ORD-2026-000007", "0412", List.of());
        assertThat(EventAttributes.forInventoryEvent(inv))
                .containsEntry("eventType", "INVENTORY_BACKORDERED")
                .containsEntry("source", "INVENTORY_SERVICE")
                .containsEntry("storeId", "0412")
                .containsEntry("orderId", "ORD-2026-000007");

        ShipmentEvent ship = new ShipmentEvent("s1", ShipmentEvent.EVENT_TYPE_SHIPMENT_UPDATED, Instant.now(), "1",
                ShipmentEvent.SOURCE_SHIPMENT_WEBHOOK, "c4", "ORD-2026-000007", "1Z1", "UPS",
                ShipmentStatus.DELIVERED, Instant.now(), null);
        assertThat(EventAttributes.forShipmentEvent(ship))
                .containsEntry("eventType", "SHIPMENT_UPDATED")
                .containsEntry("source", "SHIPMENT_WEBHOOK")
                .containsEntry("carrier", "UPS")
                .doesNotContainKey("storeId");
    }
}

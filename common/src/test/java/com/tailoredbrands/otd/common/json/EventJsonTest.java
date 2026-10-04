package com.tailoredbrands.otd.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.InventoryLine;
import com.tailoredbrands.otd.common.event.InventoryLineStatus;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventJsonTest {

    static OrderEvent sampleOrderEvent() {
        Order order = new Order("ORD-2026-000123", OrderType.TAILORED, Channel.STORE, "0412", "C-77812",
                Instant.parse("2026-10-03T22:14:00Z"), LocalDate.of(2026, 10, 10), "USD",
                new BigDecimal("649.99"),
                List.of(
                        new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"),
                                FulfillmentType.STORE_PICKUP, null),
                        new OrderLine(2, "ALT-HEM-TROUSER", 1, new BigDecimal("50.00"),
                                FulfillmentType.ALTERATION,
                                new Alteration("HEM", new BigDecimal("31.5"), "TS-EASTBAY"))),
                null, null);
        return new OrderEvent("6f1c0c8e-6f2a-4d8c-9a8e-4b6b0e2a9c11", OrderEventType.ORDER_CREATED,
                Instant.parse("2026-10-03T22:14:05.120Z"), "1", EventSource.ORDER_INTAKE_API,
                "store-0412-txn-889213", null, order);
    }

    @Test
    void orderEventRoundTripsAndMatchesArchitectureExample() throws Exception {
        OrderEvent event = sampleOrderEvent();

        String json = EventJson.toJson(event);
        JsonNode tree = EventJson.MAPPER.readTree(json);

        // envelope fields, lowerCamelCase, RFC-3339 timestamps as strings
        assertThat(tree.get("eventId").asText()).isEqualTo("6f1c0c8e-6f2a-4d8c-9a8e-4b6b0e2a9c11");
        assertThat(tree.get("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(tree.get("eventTime").asText()).isEqualTo("2026-10-03T22:14:05.120Z");
        assertThat(tree.get("schemaVersion").asText()).isEqualTo("1");
        assertThat(tree.get("source").asText()).isEqualTo("ORDER_INTAKE_API");
        // nulls are omitted so proto-JSON schema validation accepts the payload
        assertThat(tree.has("legacyMessageId")).isFalse();
        assertThat(tree.get("order").has("rental")).isFalse();
        assertThat(tree.get("order").has("shipTo")).isFalse();
        // order fields
        JsonNode order = tree.get("order");
        assertThat(order.get("orderedAt").asText()).isEqualTo("2026-10-03T22:14:00Z");
        assertThat(order.get("promisedDate").asText()).isEqualTo("2026-10-10");
        assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("649.99");
        assertThat(order.get("lines")).hasSize(2);
        assertThat(order.get("lines").get(1).get("alteration").get("measurementInches").decimalValue())
                .isEqualByComparingTo("31.5");
        assertThat(order.get("lines").get(0).has("alteration")).isFalse();

        OrderEvent back = EventJson.fromJson(json, OrderEvent.class);
        assertThat(back).isEqualTo(event);
    }

    @Test
    void unknownPropertiesAreIgnored() {
        String json = """
                {"eventId":"e1","eventType":"ORDER_CANCELLED","eventTime":"2026-01-01T00:00:00Z",
                 "schemaVersion":"1","source":"REPLAY","correlationId":"c1","futureField":{"x":1},
                 "order":{"orderId":"ORD-2026-000001","orderType":"RETAIL","channel":"STORE","storeId":"0875",
                          "orderedAt":"2026-01-01T00:00:00Z","currency":"USD","totalAmount":10,
                          "lines":[{"lineNumber":1,"sku":"X","quantity":1,"unitPrice":10,"fulfillmentType":"STORE_PICKUP",
                                    "extra":"ignored"}]}}
                """;
        OrderEvent event = EventJson.fromJson(json, OrderEvent.class);
        assertThat(event.eventType()).isEqualTo(OrderEventType.ORDER_CANCELLED);
        assertThat(event.source()).isEqualTo(EventSource.REPLAY);
        assertThat(event.order().lines()).hasSize(1);
        assertThat(event.order().promisedDate()).isNull();
    }

    @Test
    void inventoryEventRoundTrips() {
        InventoryEvent event = new InventoryEvent("e2", InventoryEventType.INVENTORY_RESERVED,
                Instant.parse("2026-10-03T22:14:06Z"), "1", InventoryEvent.SOURCE_INVENTORY_SERVICE, "c1",
                "ORD-2026-000123", "0412",
                List.of(new InventoryLine(1, "MW-SUIT-NAVY-42R", 1, InventoryLineStatus.RESERVED, "0412")));
        String json = EventJson.toJson(event);
        assertThat(json).contains("\"eventType\":\"INVENTORY_RESERVED\"").contains("\"locationId\":\"0412\"");
        assertThat(EventJson.fromJson(json, InventoryEvent.class)).isEqualTo(event);
    }

    @Test
    void shipmentEventRoundTrips() {
        ShipmentEvent event = new ShipmentEvent("e3", ShipmentEvent.EVENT_TYPE_SHIPMENT_UPDATED,
                Instant.parse("2026-10-04T15:00:00Z"), "1", ShipmentEvent.SOURCE_SHIPMENT_WEBHOOK, "c2",
                "ORD-2026-000123", "1Z999AA10123456784", "UPS", ShipmentStatus.IN_TRANSIT,
                Instant.parse("2026-10-04T14:58:00Z"), "Oakland, CA");
        String json = EventJson.toJson(event);
        assertThat(json).contains("\"status\":\"IN_TRANSIT\"").contains("\"statusTime\":\"2026-10-04T14:58:00Z\"");
        assertThat(EventJson.fromJson(json, ShipmentEvent.class)).isEqualTo(event);
    }
}

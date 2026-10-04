package com.tailoredbrands.otd.orderintake.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.Address;
import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.event.Rental;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.orderintake.error.InvalidOrderException;
import com.tailoredbrands.otd.orderintake.legacy.LegacyOrderXmlWriter;
import com.tailoredbrands.otd.orderintake.service.OrderValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyOrderTransformerTest {

    private final LegacyOrderTransformer transformer = new LegacyOrderTransformer();

    private static String resource(String name) throws IOException {
        try (InputStream in = LegacyOrderTransformerTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("test resource " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void transformsSoapSampleIntoCanonicalJson() throws IOException {
        String json = transformer.toCanonicalJson(resource("legacy-order-sample.xml"));
        JsonNode tree = EventJson.MAPPER.readTree(json);

        assertThat(tree.get("orderType").asText()).isEqualTo("TAILORED");       // T
        assertThat(tree.get("channel").asText()).isEqualTo("STORE");
        assertThat(tree.get("storeId").asText()).isEqualTo("0412");
        assertThat(tree.get("customerId").asText()).isEqualTo("C-77812");
        assertThat(tree.get("orderedAt").asText()).isEqualTo("2026-10-03T22:14:00Z");
        assertThat(tree.get("promisedDate").asText()).isEqualTo("2026-10-10");
        assertThat(tree.get("currency").asText()).isEqualTo("USD");
        assertThat(tree.get("totalAmount").decimalValue()).isEqualByComparingTo("649.99");
        assertThat(tree.get("lines")).hasSize(2);
        assertThat(tree.get("lines").get(0).get("fulfillmentType").asText()).isEqualTo("STORE_PICKUP"); // P
        assertThat(tree.get("lines").get(1).get("fulfillmentType").asText()).isEqualTo("ALTERATION");   // A
        assertThat(tree.get("lines").get(1).get("alteration").get("tailorShopId").asText()).isEqualTo("TS-EASTBAY");
        assertThat(tree.has("orderId")).isFalse();
    }

    @Test
    void parsesIntoCanonicalOrderThatPassesBusinessValidation() throws IOException {
        Order order = transformer.toCanonicalOrder(resource("legacy-order-sample.xml"));

        assertThat(order.orderType()).isEqualTo(OrderType.TAILORED);
        assertThat(order.lines()).extracting(OrderLine::sku).containsExactly("MW-SUIT-NAVY-42R", "ALT-HEM-TROUSER");
        assertThat(order.lines().get(1).alteration()).isEqualTo(new Alteration("HEM", new BigDecimal("31.5"), "TS-EASTBAY"));
        new OrderValidator().validate(order); // must not throw
    }

    @Test
    void handlesBareOrderWithoutNamespaceEcomCodesEscapingAndMissingZone() throws IOException {
        Order order = transformer.toCanonicalOrder(resource("legacy-order-ecom-sample.xml"));

        assertThat(order.orderType()).isEqualTo(OrderType.ECOM);                 // E
        assertThat(order.channel()).isEqualTo(Channel.WEB);
        assertThat(order.orderedAt()).isEqualTo(Instant.parse("2026-10-03T15:30:00Z")); // zone appended
        assertThat(order.lines().get(0).fulfillmentType()).isEqualTo(FulfillmentType.SHIP_TO_HOME); // S
        assertThat(order.totalAmount()).isEqualByComparingTo("159.00");
        assertThat(order.shipTo()).isEqualTo(new Address("Jane \"JJ\" O'Neil", "1 Main St", null, "Oakland", "CA",
                "94612", "US"));
    }

    @Test
    void rejectsUnknownOrderTypeCode() {
        String xml = """
                <Order><OrderNbr>X1</OrderNbr><OrderType>Q</OrderType><StoreNbr>0412</StoreNbr>
                <OrderDate>2026-10-03T22:14:00Z</OrderDate><Lines><Line><LineNbr>1</LineNbr><SKU>A</SKU><Qty>1</Qty>
                <Price>1</Price><FulfillType>P</FulfillType></Line></Lines></Order>
                """;
        // the JSON helper reports the problem in _errors ...
        assertThat(transformer.toCanonicalJson(xml)).contains("\"orderType\":null").contains("unknown OrderType code Q");
        // ... and the typed path rejects the order
        assertThatThrownBy(() -> transformer.toCanonicalOrder(xml))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("unknown OrderType code Q");
    }

    @Test
    void rejectsXmlWithoutOrderElement() {
        assertThatThrownBy(() -> transformer.toCanonicalOrder("<Foo/>"))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("no Order element");
    }

    @Test
    void rejectsNonXml() {
        assertThatThrownBy(() -> transformer.toCanonicalJson("{\"not\":\"xml\"}"))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> transformer.toCanonicalJson("  "))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void roundTripsThroughTheReverseWriter() {
        Order original = new Order("ORD-2026-000123", OrderType.RENTAL, Channel.STORE, "0875", "C-9",
                Instant.parse("2026-10-03T22:14:00Z"), LocalDate.of(2026, 11, 1), "USD", new BigDecimal("289.00"),
                List.of(
                        new OrderLine(1, "TUX-RENTAL-BLACK-42R", 1, new BigDecimal("249.00"), FulfillmentType.STORE_PICKUP, null),
                        new OrderLine(2, "ALT-HEM-TROUSER", 1, new BigDecimal("40.00"), FulfillmentType.ALTERATION,
                                new Alteration("HEM", new BigDecimal("30.25"), "TS-IN-STORE"))),
                new Rental("EVT-WED-2026-11-07", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9), "GRP-SMITH"),
                null);

        String legacyXml = new LegacyOrderXmlWriter().write(original);
        assertThat(legacyXml).contains("<OrderType>X</OrderType>").contains("<FulfillType>A</FulfillType>");

        Order back = transformer.toCanonicalOrder(legacyXml);

        // orderId is not part of the canonical draft (it is re-assigned by OrderService)
        assertThat(back.withOrderId(original.orderId())).isEqualTo(original);
    }
}

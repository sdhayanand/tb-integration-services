package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.orderintake.error.InvalidOrderException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    private static Order order(BigDecimal total, List<OrderLine> lines) {
        return new Order(null, OrderType.TAILORED, Channel.STORE, "0412", "C-1", Instant.parse("2026-10-03T22:14:00Z"),
                null, "USD", total, lines, null, null);
    }

    @Test
    void acceptsValidTailoredOrder() {
        Order order = order(new BigDecimal("649.99"), List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null),
                new OrderLine(2, "ALT-HEM-TROUSER", 1, new BigDecimal("50.00"), FulfillmentType.ALTERATION,
                        new Alteration("HEM", new BigDecimal("31.5"), "TS-EASTBAY"))));
        assertThatCode(() -> validator.validate(order)).doesNotThrowAnyException();
    }

    @Test
    void reportsAllViolationsAtOnce() {
        Order order = new Order(null, OrderType.RENTAL, null, "41", null, null, null, "usd", new BigDecimal("1.00"),
                List.of(
                        new OrderLine(1, "", 0, new BigDecimal("-1"), FulfillmentType.ALTERATION, null),
                        new OrderLine(1, "SKU", 1, new BigDecimal("1.00"), FulfillmentType.SHIP_TO_HOME,
                                new Alteration("HEM", null, null))),
                null, null);

        assertThatThrownBy(() -> validator.validate(order))
                .isInstanceOf(InvalidOrderException.class)
                .satisfies(ex -> {
                    List<String> v = ((InvalidOrderException) ex).violations();
                    org.assertj.core.api.Assertions.assertThat(v)
                            .anyMatch(s -> s.contains("channel is required"))
                            .anyMatch(s -> s.contains("storeId must be 4 digits"))
                            .anyMatch(s -> s.contains("orderedAt is required"))
                            .anyMatch(s -> s.contains("currency"))
                            .anyMatch(s -> s.contains("sku is required"))
                            .anyMatch(s -> s.contains("quantity must be positive"))
                            .anyMatch(s -> s.contains("unitPrice"))
                            .anyMatch(s -> s.contains("alteration.type is required"))
                            .anyMatch(s -> s.contains("duplicate lineNumber"))
                            .anyMatch(s -> s.contains("alteration is only allowed"))
                            .anyMatch(s -> s.contains("shipTo address is required"))
                            .anyMatch(s -> s.contains("rental details are required"));
                });
    }

    @Test
    void rejectsTotalMismatch() {
        Order order = order(new BigDecimal("100.00"), List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 2, new BigDecimal("10.00"), FulfillmentType.STORE_PICKUP, null)));
        assertThatThrownBy(() -> validator.validate(order))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("totalAmount 100.00 does not match sum of lines 20.00");
    }

    @Test
    void rejectsEmptyLines() {
        assertThatThrownBy(() -> validator.validate(order(BigDecimal.ZERO, List.of())))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("lines must not be empty");
    }
}

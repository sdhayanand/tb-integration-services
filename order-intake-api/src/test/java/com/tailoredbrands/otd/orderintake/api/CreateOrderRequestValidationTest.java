package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static CreateOrderLineRequest line(String sku, int qty, String price, FulfillmentType type) {
        return new CreateOrderLineRequest(null, sku, qty, new BigDecimal(price), type, null);
    }

    @Test
    void validRequestHasNoViolations() {
        CreateOrderRequest request = new CreateOrderRequest(OrderType.RETAIL, null, "0412", "C-1", null, null, null,
                null, List.of(line("MW-SUIT-NAVY-42R", 1, "599.99", FulfillmentType.STORE_PICKUP)), null, null);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void storeIdMustBeFourDigits() {
        CreateOrderRequest request = new CreateOrderRequest(OrderType.RETAIL, null, "41A", null, null, null, null,
                null, List.of(line("MW-SUIT-NAVY-42R", 1, "599.99", FulfillmentType.STORE_PICKUP)), null, null);
        assertThat(paths(validator.validate(request))).containsExactly("storeId");
    }

    @Test
    void linesMustNotBeEmptyAndOrderTypeIsRequired() {
        CreateOrderRequest request = new CreateOrderRequest(null, null, "0412", null, null, null, null,
                null, List.of(), null, null);
        assertThat(paths(validator.validate(request))).containsExactlyInAnyOrder("orderType", "lines");
    }

    @Test
    void lineConstraintsAreCascaded() {
        CreateOrderRequest request = new CreateOrderRequest(OrderType.RETAIL, null, "0412", null, null, null, "USD",
                null, List.of(new CreateOrderLineRequest(0, "bad sku", 0, new BigDecimal("1.999"), null, null)),
                null, null);
        Set<String> paths = paths(validator.validate(request));
        assertThat(paths).contains("lines[0].lineNumber", "lines[0].sku", "lines[0].quantity",
                "lines[0].unitPrice", "lines[0].fulfillmentType");
    }

    @Test
    void currencyMustBeIso4217Upper() {
        CreateOrderRequest request = new CreateOrderRequest(OrderType.RETAIL, null, "0412", null, null, null, "usd",
                null, List.of(line("X", 1, "1.00", FulfillmentType.STORE_PICKUP)), null, null);
        assertThat(paths(validator.validate(request))).containsExactly("currency");
    }

    @Test
    void toOrderAppliesDefaultsAndComputesTotal() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-03T22:14:00Z"), ZoneOffset.UTC);
        CreateOrderRequest request = new CreateOrderRequest(OrderType.TAILORED, null, "0412", "C-77812", null, null,
                null, null, List.of(
                        line("MW-SUIT-NAVY-42R", 1, "599.99", FulfillmentType.STORE_PICKUP),
                        line("MW-SHIRT-WHITE-16", 2, "25.00", FulfillmentType.STORE_PICKUP)),
                null, null);

        Order order = request.toOrder(clock);

        assertThat(order.orderId()).isNull();
        assertThat(order.channel()).isEqualTo(Channel.STORE);
        assertThat(order.currency()).isEqualTo("USD");
        assertThat(order.orderedAt()).isEqualTo(Instant.parse("2026-10-03T22:14:00Z"));
        assertThat(order.totalAmount()).isEqualByComparingTo("649.99");
        assertThat(order.lines()).extracting("lineNumber").containsExactly(1, 2);
    }

    private static Set<String> paths(Set<ConstraintViolation<CreateOrderRequest>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }
}

package com.tailoredbrands.otd.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Canonical order (ARCHITECTURE §3.1, {@code order} element of the envelope).
 *
 * @param orderId      {@code ORD-yyyy-nnnnnn}; {@code null} on a draft that has not been persisted yet
 * @param orderType    retail / tailored / custom / rental / ecom
 * @param channel      channel the order came from
 * @param storeId      4-digit store number; also the Pub/Sub ordering key
 * @param customerId   customer id (nullable for anonymous retail)
 * @param orderedAt    when the order was taken
 * @param promisedDate promised pickup / delivery date (nullable)
 * @param currency     ISO-4217 code
 * @param totalAmount  order total
 * @param lines        order lines (never empty)
 * @param rental       rental details (RENTAL orders only)
 * @param shipTo       ship-to address (ship-to-home lines only)
 */
public record Order(
        String orderId,
        OrderType orderType,
        Channel channel,
        String storeId,
        String customerId,
        Instant orderedAt,
        LocalDate promisedDate,
        String currency,
        BigDecimal totalAmount,
        List<OrderLine> lines,
        Rental rental,
        Address shipTo) {

    public Order {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Returns a copy of this order with the given id (used once the id has been generated). */
    public Order withOrderId(String newOrderId) {
        return new Order(newOrderId, orderType, channel, storeId, customerId, orderedAt, promisedDate,
                currency, totalAmount, lines, rental, shipTo);
    }
}

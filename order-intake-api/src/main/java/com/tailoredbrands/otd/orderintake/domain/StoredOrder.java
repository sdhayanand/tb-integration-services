package com.tailoredbrands.otd.orderintake.domain;

import com.tailoredbrands.otd.common.event.Order;

import java.time.Instant;

/** An order as persisted in {@code orders} + {@code order_lines}. */
public record StoredOrder(Order order, OrderStatus status, String correlationId, Instant createdAt, Instant updatedAt) {
}

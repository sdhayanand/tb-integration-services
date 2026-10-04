package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.orderintake.domain.OrderStatus;

/** {@code 201 Created} body of {@code POST /v1/orders}. */
public record OrderCreatedResponse(String orderId, OrderStatus status) {
}

package com.tailoredbrands.otd.orderintake.domain;

/** Lifecycle of an order as tracked in {@code orders.status}. */
public enum OrderStatus {
    CREATED, RESERVED, BACKORDERED, CANCELLED
}

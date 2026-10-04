package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.event.OrderEvent;

/**
 * In-process Spring application event raised when an order has been written (committed) -
 * consumed by the migration dual-writer. The authoritative path is the outbox; this is a side channel.
 */
public record OrderCreatedEvent(OrderEvent event) {
}

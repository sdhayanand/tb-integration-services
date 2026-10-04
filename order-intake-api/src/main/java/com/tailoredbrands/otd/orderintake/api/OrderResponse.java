package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Address;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.event.Rental;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.domain.StoredOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** {@code GET /v1/orders/{id}} body: the canonical order fields plus status and audit timestamps. */
public record OrderResponse(
        String orderId,
        OrderStatus status,
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
        Address shipTo,
        String correlationId,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(StoredOrder stored) {
        var o = stored.order();
        return new OrderResponse(o.orderId(), stored.status(), o.orderType(), o.channel(), o.storeId(),
                o.customerId(), o.orderedAt(), o.promisedDate(), o.currency(), o.totalAmount(), o.lines(),
                o.rental(), o.shipTo(), stored.correlationId(), stored.createdAt(), stored.updatedAt());
    }
}

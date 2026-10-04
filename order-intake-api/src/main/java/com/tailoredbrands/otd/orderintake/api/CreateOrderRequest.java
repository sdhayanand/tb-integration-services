package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /v1/orders} body. Shape validation lives here (Jakarta), cross-field business rules
 * in {@code OrderValidator}. Defaults: channel STORE, orderedAt now, currency USD,
 * totalAmount = sum(quantity * unitPrice), lineNumber = position.
 */
@Schema(name = "CreateOrderRequest")
public record CreateOrderRequest(
        @NotNull OrderType orderType,
        Channel channel,
        @NotNull @Pattern(regexp = "\\d{4}", message = "must be a 4-digit store number") String storeId,
        @Size(max = 64) String customerId,
        Instant orderedAt,
        LocalDate promisedDate,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code") String currency,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal totalAmount,
        @NotEmpty @Size(max = 200) @Valid List<CreateOrderLineRequest> lines,
        @Valid RentalRequest rental,
        @Valid AddressRequest shipTo) {

    /** Converts to a canonical order draft (no orderId yet), applying defaults. */
    public Order toOrder(Clock clock) {
        List<OrderLine> orderLines = new ArrayList<>();
        BigDecimal computed = BigDecimal.ZERO;
        int position = 1;
        for (CreateOrderLineRequest line : lines) {
            OrderLine orderLine = line.toOrderLine(position++);
            orderLines.add(orderLine);
            computed = computed.add(orderLine.unitPrice().multiply(BigDecimal.valueOf(orderLine.quantity())));
        }
        return new Order(
                null,
                orderType,
                channel == null ? Channel.STORE : channel,
                storeId,
                customerId,
                orderedAt == null ? Instant.now(clock) : orderedAt,
                promisedDate,
                currency == null ? "USD" : currency,
                totalAmount == null ? computed.setScale(2, RoundingMode.HALF_UP) : totalAmount,
                orderLines,
                rental == null ? null : rental.toRental(),
                shipTo == null ? null : shipTo.toAddress());
    }
}

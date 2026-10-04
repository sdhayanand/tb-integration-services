package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.orderintake.error.InvalidOrderException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business rules applied to a canonical {@link Order} regardless of the entry path (REST DTO
 * validation covers shape; this covers cross-field rules and the SOAP/XSLT path).
 */
@Component
public class OrderValidator {

    private static final Pattern STORE_ID = Pattern.compile("\\d{4}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_LINES = 200;

    public void validate(Order order) {
        List<String> errors = new ArrayList<>();
        if (order.orderType() == null) {
            errors.add("orderType is required");
        }
        if (order.channel() == null) {
            errors.add("channel is required");
        }
        if (order.storeId() == null || !STORE_ID.matcher(order.storeId()).matches()) {
            errors.add("storeId must be 4 digits");
        }
        if (order.orderedAt() == null) {
            errors.add("orderedAt is required");
        }
        if (order.currency() == null || !CURRENCY.matcher(order.currency()).matches()) {
            errors.add("currency must be an ISO-4217 code");
        }
        if (order.lines().isEmpty()) {
            errors.add("lines must not be empty");
        } else if (order.lines().size() > MAX_LINES) {
            errors.add("lines must not exceed " + MAX_LINES);
        }

        Set<Integer> lineNumbers = new HashSet<>();
        BigDecimal computedTotal = BigDecimal.ZERO;
        for (OrderLine line : order.lines()) {
            String prefix = "line " + line.lineNumber() + ": ";
            if (line.lineNumber() <= 0) {
                errors.add(prefix + "lineNumber must be positive");
            } else if (!lineNumbers.add(line.lineNumber())) {
                errors.add(prefix + "duplicate lineNumber");
            }
            if (line.sku() == null || line.sku().isBlank()) {
                errors.add(prefix + "sku is required");
            }
            if (line.quantity() <= 0) {
                errors.add(prefix + "quantity must be positive");
            }
            if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
                errors.add(prefix + "unitPrice must be >= 0");
            } else {
                computedTotal = computedTotal.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            }
            if (line.fulfillmentType() == null) {
                errors.add(prefix + "fulfillmentType is required");
            } else if (line.fulfillmentType() == FulfillmentType.ALTERATION) {
                if (line.alteration() == null || line.alteration().type() == null || line.alteration().type().isBlank()) {
                    errors.add(prefix + "alteration.type is required for ALTERATION lines");
                }
            } else if (line.alteration() != null) {
                errors.add(prefix + "alteration is only allowed on ALTERATION lines");
            }
            if (line.fulfillmentType() == FulfillmentType.SHIP_TO_HOME && order.shipTo() == null) {
                errors.add(prefix + "shipTo address is required for SHIP_TO_HOME lines");
            }
        }

        if (order.totalAmount() == null) {
            errors.add("totalAmount is required");
        } else if (order.totalAmount().compareTo(computedTotal) != 0) {
            errors.add("totalAmount " + order.totalAmount().toPlainString()
                    + " does not match sum of lines " + computedTotal.toPlainString());
        }
        if (order.orderType() == OrderType.RENTAL && order.rental() == null) {
            errors.add("rental details are required for RENTAL orders");
        }
        if (!errors.isEmpty()) {
            throw new InvalidOrderException(errors);
        }
    }
}

package com.tailoredbrands.otd.common.event;

import java.math.BigDecimal;

/**
 * One line of a canonical {@link Order}.
 *
 * @param lineNumber      1-based line number, unique within the order
 * @param sku             SKU, e.g. {@code MW-SUIT-NAVY-42R}
 * @param quantity        quantity ordered (&gt; 0)
 * @param unitPrice       unit price in the order currency
 * @param fulfillmentType how the line is fulfilled
 * @param alteration      alteration details; only when {@code fulfillmentType == ALTERATION}
 */
public record OrderLine(
        int lineNumber,
        String sku,
        int quantity,
        BigDecimal unitPrice,
        FulfillmentType fulfillmentType,
        Alteration alteration) {
}

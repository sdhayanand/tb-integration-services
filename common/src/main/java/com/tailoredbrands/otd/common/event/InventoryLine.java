package com.tailoredbrands.otd.common.event;

/**
 * Per-line outcome inside an {@link InventoryEvent}.
 *
 * @param lineNumber order line number
 * @param sku        sku
 * @param quantity   quantity requested
 * @param status     reserved / backordered / released
 * @param locationId location that holds the reservation ({@code null} when backordered)
 */
public record InventoryLine(
        int lineNumber,
        String sku,
        int quantity,
        InventoryLineStatus status,
        String locationId) {
}

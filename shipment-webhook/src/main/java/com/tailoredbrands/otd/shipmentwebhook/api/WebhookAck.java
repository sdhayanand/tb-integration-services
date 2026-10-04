package com.tailoredbrands.otd.shipmentwebhook.api;

import com.tailoredbrands.otd.common.event.ShipmentStatus;

/** {@code 202 Accepted} body. */
public record WebhookAck(String eventId, String orderId, String trackingNumber, String carrier, ShipmentStatus status,
                         String messageId) {
}

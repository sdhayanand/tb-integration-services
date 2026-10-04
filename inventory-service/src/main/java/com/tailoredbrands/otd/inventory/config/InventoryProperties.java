package com.tailoredbrands.otd.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code otd.*} settings (env vars of ARCHITECTURE §6: PUBSUB_PROJECT, ORDERS_SUBSCRIPTION, INVENTORY_TOPIC). */
@ConfigurationProperties(prefix = "otd")
public record InventoryProperties(PubSub pubsub, Stock stock) {

    public record PubSub(String project, String emulatorHost, String ordersSubscription, String inventoryTopic) {
    }

    /** {@code fallbackLocation}: the DC that backs every store (DC01). */
    public record Stock(String fallbackLocation) {
    }
}

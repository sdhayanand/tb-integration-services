package com.tailoredbrands.otd.orderintake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code otd.*} configuration (bound from application.yml, which maps the env vars of ARCHITECTURE §6).
 */
@ConfigurationProperties(prefix = "otd")
public record OtdProperties(PubSub pubsub, Outbox outbox, Migration migration, Legacy legacy) {

    /** Pub/Sub settings. {@code emulatorHost} empty = real API. */
    public record PubSub(String project, String emulatorHost, String ordersTopic, String inventorySubscription) {
    }

    /** Outbox relay tuning. */
    public record Outbox(int batchSize, long relayDelayMs) {
    }

    /** Migration phase (ARCHITECTURE §7): LEGACY_ONLY, SHADOW, DUAL_RUN, PUBSUB_PRIMARY, CUTOVER. */
    public record Migration(String phase) {
    }

    /** Legacy EMS stand-in (Artemis) and OMS SOAP endpoint. */
    public record Legacy(String jmsUrl, String jmsUser, String jmsPassword, String jmsQueue, String omsSoapUrl) {
    }
}

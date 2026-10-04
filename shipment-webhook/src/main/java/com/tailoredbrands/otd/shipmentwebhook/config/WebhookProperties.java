package com.tailoredbrands.otd.shipmentwebhook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Env vars per ARCHITECTURE §6: PUBSUB_PROJECT, SHIPMENTS_TOPIC, WEBHOOK_SHARED_SECRET. */
@ConfigurationProperties(prefix = "otd")
public record WebhookProperties(PubSub pubsub, Webhook webhook) {

    public record PubSub(String project, String emulatorHost, String shipmentsTopic) {
    }

    /**
     * @param sharedSecret HMAC-SHA256 key carriers sign the raw body with ({@code X-Carrier-Signature})
     * @param allowUnsigned skip verification when the secret is empty (only honoured in the local profile)
     */
    public record Webhook(String sharedSecret, boolean allowUnsigned) {
    }
}

package com.tailoredbrands.otd.shipmentwebhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * shipment-webhook (Cloud Run): receives carrier-native tracking webhooks (UPS, FedEx), verifies the
 * HMAC signature, maps them to the canonical {@code ShipmentEvent} and publishes {@code shipments-v1}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ShipmentWebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShipmentWebhookApplication.class, args);
    }
}

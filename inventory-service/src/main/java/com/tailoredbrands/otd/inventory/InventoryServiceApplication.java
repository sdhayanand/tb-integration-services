package com.tailoredbrands.otd.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * inventory-service: exactly-once consumer of {@code orders-v1} that reserves stock at the store
 * (falling back to DC01) and publishes {@code inventory-v1}; exposes per-location availability.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

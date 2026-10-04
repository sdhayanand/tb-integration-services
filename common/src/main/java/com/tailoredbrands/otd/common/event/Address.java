package com.tailoredbrands.otd.common.event;

/**
 * Postal address (ship-to for ECOM / SHIP_TO_HOME lines).
 */
public record Address(
        String name,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country) {
}

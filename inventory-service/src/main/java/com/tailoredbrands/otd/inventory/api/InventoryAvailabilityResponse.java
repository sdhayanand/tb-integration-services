package com.tailoredbrands.otd.inventory.api;

import com.tailoredbrands.otd.inventory.repository.InventoryRepository.StockLevel;

import java.util.List;

/** {@code GET /v1/inventory/{sku}} body. */
public record InventoryAvailabilityResponse(String sku, int totalAvailable, List<Location> locations) {

    public record Location(String locationId, int onHand, int reserved, int available) {
        static Location from(StockLevel level) {
            return new Location(level.locationId(), level.onHand(), level.reserved(), level.available());
        }
    }

    public static InventoryAvailabilityResponse from(String sku, List<StockLevel> levels) {
        List<Location> locations = levels.stream().map(Location::from).toList();
        int total = locations.stream().mapToInt(Location::available).sum();
        return new InventoryAvailabilityResponse(sku, total, locations);
    }
}

package com.tailoredbrands.otd.inventory.api;

import com.tailoredbrands.otd.inventory.error.SkuNotFoundException;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository.StockLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/v1/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Inventory")
public class InventoryController {

    private final InventoryRepository repository;

    public InventoryController(InventoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Per-location availability for a SKU (on hand, reserved, available)")
    @Transactional(readOnly = true)
    public InventoryAvailabilityResponse bySku(
            @PathVariable @Pattern(regexp = "[A-Z0-9][A-Z0-9\\-]{0,63}", message = "invalid SKU") String sku) {
        List<StockLevel> levels = repository.findBySku(sku);
        if (levels.isEmpty()) {
            throw new SkuNotFoundException(sku);
        }
        return InventoryAvailabilityResponse.from(sku, levels);
    }
}

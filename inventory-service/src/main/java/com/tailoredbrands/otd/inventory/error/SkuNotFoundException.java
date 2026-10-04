package com.tailoredbrands.otd.inventory.error;

public class SkuNotFoundException extends RuntimeException {

    private final String sku;

    public SkuNotFoundException(String sku) {
        super("SKU " + sku + " is not stocked at any location");
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}

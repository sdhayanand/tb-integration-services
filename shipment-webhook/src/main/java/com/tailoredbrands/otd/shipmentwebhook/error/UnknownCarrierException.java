package com.tailoredbrands.otd.shipmentwebhook.error;

public class UnknownCarrierException extends RuntimeException {

    private final String carrier;

    public UnknownCarrierException(String carrier) {
        super("Unknown carrier '" + carrier + "' (supported: UPS, FEDEX)");
        this.carrier = carrier;
    }

    public String carrier() {
        return carrier;
    }
}

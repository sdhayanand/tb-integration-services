package com.tailoredbrands.otd.shipmentwebhook.error;

/** Missing or wrong {@code X-Carrier-Signature} (401). */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}

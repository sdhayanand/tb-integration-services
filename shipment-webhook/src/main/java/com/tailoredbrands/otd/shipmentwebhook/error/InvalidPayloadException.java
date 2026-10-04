package com.tailoredbrands.otd.shipmentwebhook.error;

/** The carrier payload is well-formed JSON but misses fields we need (400). */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String message) {
        super(message);
    }
}

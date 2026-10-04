package com.tailoredbrands.otd.orderintake.error;

public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}

package com.example.orderpayment.application;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("order not found: " + orderId);
    }
}

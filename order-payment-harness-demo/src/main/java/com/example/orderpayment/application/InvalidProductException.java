package com.example.orderpayment.application;

public class InvalidProductException extends RuntimeException {

    private final String skuId;

    public InvalidProductException(String skuId) {
        super("invalid product: " + skuId);
        this.skuId = skuId;
    }

    public String skuId() {
        return skuId;
    }
}

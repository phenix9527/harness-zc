package com.example.orderpayment.application;

    public class InsufficientInventoryException extends RuntimeException {

    private final String skuId;

    public InsufficientInventoryException(String skuId) {
        super("insufficient inventory: " + skuId);
        this.skuId = skuId;
    }

    public String skuId() {
        return skuId;
    }
}

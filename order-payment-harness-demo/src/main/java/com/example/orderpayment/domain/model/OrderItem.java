package com.example.orderpayment.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class OrderItem {

    private final String skuId;
    private final int quantity;
    private final BigDecimal unitPriceSnapshot;

    public OrderItem(String skuId, int quantity, BigDecimal unitPriceSnapshot) {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPriceSnapshot == null || unitPriceSnapshot.signum() < 0) {
            throw new IllegalArgumentException("unitPriceSnapshot must not be negative");
        }
        this.skuId = skuId;
        this.quantity = quantity;
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    public String skuId() {
        return skuId;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public BigDecimal subtotal() {
        return unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderItem orderItem)) {
            return false;
        }
        return quantity == orderItem.quantity
                && skuId.equals(orderItem.skuId)
                && unitPriceSnapshot.equals(orderItem.unitPriceSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skuId, quantity, unitPriceSnapshot);
    }
}

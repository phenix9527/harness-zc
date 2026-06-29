package com.example.orderpayment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PaymentTransaction {

    private final String transactionId;
    private final UUID orderId;
    private final BigDecimal paidAmount;
    private final Instant paidAt;

    public PaymentTransaction(String transactionId, UUID orderId, BigDecimal paidAmount, Instant paidAt) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (paidAmount == null || paidAmount.signum() < 0) {
            throw new IllegalArgumentException("paidAmount must not be negative");
        }
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt must not be null");
        }
        this.transactionId = transactionId;
        this.orderId = orderId;
        this.paidAmount = paidAmount;
        this.paidAt = paidAt;
    }

    public String transactionId() {
        return transactionId;
    }

    public UUID orderId() {
        return orderId;
    }

    public BigDecimal paidAmount() {
        return paidAmount;
    }

    public Instant paidAt() {
        return paidAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaymentTransaction that)) {
            return false;
        }
        return transactionId.equals(that.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }
}

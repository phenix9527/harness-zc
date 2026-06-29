package com.example.orderpayment.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Order {

    private final UUID id;
    private final String userId;
    private final List<OrderItem> items;
    private final BigDecimal payableAmount;
    private OrderStatus status;

    public Order(UUID id, String userId, List<OrderItem> items) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        this.id = id;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.payableAmount = this.items.stream()
                .map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    public UUID id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public List<OrderItem> items() {
        return items;
    }

    public BigDecimal payableAmount() {
        return payableAmount;
    }

    public OrderStatus status() {
        return status;
    }

    public void markPaid() {
        if (status == OrderStatus.PAID) {
            return;
        }
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("only pending payment orders can be marked paid");
        }
        status = OrderStatus.PAID;
    }

    public void cancel() {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("only pending payment orders can be cancelled");
        }
        status = OrderStatus.CANCELLED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order order)) {
            return false;
        }
        return id.equals(order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

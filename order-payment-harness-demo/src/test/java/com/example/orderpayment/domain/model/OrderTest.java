package com.example.orderpayment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void createsPendingPaymentOrderWithPriceSnapshotTotal() {
        Order order = new Order(
                UUID.randomUUID(),
                "user-1",
                List.of(
                        new OrderItem("sku-1", 2, new BigDecimal("12.50")),
                        new OrderItem("sku-2", 1, new BigDecimal("8.00"))));

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.payableAmount()).isEqualByComparingTo("33.00");
        assertThat(order.items()).hasSize(2);
    }

    @Test
    void marksPendingOrderAsPaid() {
        Order order = sampleOrder();

        order.markPaid();

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void ignoresMarkPaidWhenOrderAlreadyPaid() {
        Order order = sampleOrder();
        order.markPaid();

        order.markPaid();

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rejectsMarkPaidWhenOrderCancelled() {
        Order order = sampleOrder();
        order.cancel();

        assertThatThrownBy(order::markPaid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("only pending payment orders can be marked paid");
    }

    @Test
    void rejectsOrderWithoutItems() {
        assertThatThrownBy(() -> new Order(UUID.randomUUID(), "user-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("items must not be empty");
    }

    private Order sampleOrder() {
        return new Order(
                UUID.randomUUID(),
                "user-1",
                List.of(new OrderItem("sku-1", 1, new BigDecimal("19.90"))));
    }
}

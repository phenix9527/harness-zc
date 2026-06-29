package com.example.orderpayment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderItemTest {

    @Test
    void calculatesSubtotalFromPriceSnapshotAndQuantity() {
        OrderItem item = new OrderItem("sku-1", 3, new BigDecimal("4.20"));

        assertThat(item.subtotal()).isEqualByComparingTo("12.60");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new OrderItem("sku-1", 0, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be positive");
    }
}

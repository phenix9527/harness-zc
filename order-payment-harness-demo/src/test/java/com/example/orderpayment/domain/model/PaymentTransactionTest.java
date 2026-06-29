package com.example.orderpayment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTransactionTest {

    @Test
    void createsPaymentTransaction() {
        UUID orderId = UUID.randomUUID();
        Instant paidAt = Instant.parse("2026-06-29T12:00:00Z");

        PaymentTransaction transaction = new PaymentTransaction(
                "txn-1",
                orderId,
                new BigDecimal("19.90"),
                paidAt);

        assertThat(transaction.transactionId()).isEqualTo("txn-1");
        assertThat(transaction.orderId()).isEqualTo(orderId);
        assertThat(transaction.paidAmount()).isEqualByComparingTo("19.90");
        assertThat(transaction.paidAt()).isEqualTo(paidAt);
    }

    @Test
    void rejectsBlankTransactionId() {
        assertThatThrownBy(() -> new PaymentTransaction(
                " ",
                UUID.randomUUID(),
                BigDecimal.ONE,
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transactionId must not be blank");
    }
}

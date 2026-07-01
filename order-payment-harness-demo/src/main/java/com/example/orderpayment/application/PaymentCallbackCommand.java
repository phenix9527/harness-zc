package com.example.orderpayment.application;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCallbackCommand(
        UUID orderId,
        String transactionId,
        BigDecimal paidAmount,
        String signature) {
}

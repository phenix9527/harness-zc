package com.example.orderpayment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PaymentCallbackRequest(
        @NotBlank String orderId,
        @NotBlank String transactionId,
        @NotNull BigDecimal paidAmount,
        @NotBlank String signature) {
}

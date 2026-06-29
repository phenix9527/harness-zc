package com.example.orderpayment.application;

import com.example.orderpayment.domain.model.OrderStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderResult(
        UUID orderId,
        OrderStatus orderStatus,
        BigDecimal payableAmount,
        String paymentToken) {
}

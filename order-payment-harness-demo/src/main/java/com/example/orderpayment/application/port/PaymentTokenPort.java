package com.example.orderpayment.application.port;

import com.example.orderpayment.domain.model.Order;

public interface PaymentTokenPort {

    String createPaymentToken(Order order);
}

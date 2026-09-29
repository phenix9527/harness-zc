package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.PaymentTokenPort;
import com.example.orderpayment.domain.model.Order;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InMemoryPaymentTokenAdapter implements PaymentTokenPort {

    @Override
    public String createPaymentToken(Order order) {
        return "pay_" + order.id() + "_" + UUID.randomUUID();
    }
}

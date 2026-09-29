package com.example.orderpayment.application.port;

import com.example.orderpayment.domain.model.Order;

public interface OrderPaidEventPublisher {

    void publish(OrderPaidEvent event);

    record OrderPaidEvent(String transactionId, Order order) {
    }
}

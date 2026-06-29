package com.example.orderpayment.application.port;

import com.example.orderpayment.domain.model.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(UUID orderId);
}

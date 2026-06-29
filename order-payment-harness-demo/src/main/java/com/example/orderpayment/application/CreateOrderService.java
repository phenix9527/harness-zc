package com.example.orderpayment.application;

import com.example.orderpayment.application.port.InventoryAvailabilityPort;
import com.example.orderpayment.application.port.OrderRepository;
import com.example.orderpayment.application.port.PaymentTokenPort;
import com.example.orderpayment.application.port.ProductCatalogPort;
import com.example.orderpayment.domain.model.Order;
import com.example.orderpayment.domain.model.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreateOrderService {

    private final ProductCatalogPort productCatalogPort;
    private final InventoryAvailabilityPort inventoryAvailabilityPort;
    private final OrderRepository orderRepository;
    private final PaymentTokenPort paymentTokenPort;

    public CreateOrderService(
            ProductCatalogPort productCatalogPort,
            InventoryAvailabilityPort inventoryAvailabilityPort,
            OrderRepository orderRepository,
            PaymentTokenPort paymentTokenPort) {
        this.productCatalogPort = productCatalogPort;
        this.inventoryAvailabilityPort = inventoryAvailabilityPort;
        this.orderRepository = orderRepository;
        this.paymentTokenPort = paymentTokenPort;
    }

    public CreateOrderResult create(CreateOrderCommand command) {
        validate(command);

        List<OrderItem> orderItems = command.items().stream()
                .map(item -> {
                    ProductCatalogPort.ProductSnapshot product = productCatalogPort.findSellableProduct(item.skuId())
                            .orElseThrow(() -> new InvalidProductException(item.skuId()));
                    if (!inventoryAvailabilityPort.hasAvailableInventory(item.skuId(), item.quantity())) {
                        throw new InsufficientInventoryException(item.skuId());
                    }
                    return new OrderItem(item.skuId(), item.quantity(), product.price());
                })
                .toList();

        Order order = new Order(UUID.randomUUID(), command.userId(), orderItems);
        orderRepository.save(order);
        String paymentToken = paymentTokenPort.createPaymentToken(order);

        return new CreateOrderResult(order.id(), order.status(), order.payableAmount(), paymentToken);
    }

    private void validate(CreateOrderCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}

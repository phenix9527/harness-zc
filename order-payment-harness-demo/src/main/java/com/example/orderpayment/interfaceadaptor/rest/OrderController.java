package com.example.orderpayment.interfaceadaptor.rest;

import com.example.orderpayment.application.CreateOrderCommand;
import com.example.orderpayment.application.CreateOrderRequest;
import com.example.orderpayment.application.CreateOrderResult;
import com.example.orderpayment.application.CreateOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CreateOrderService createOrderService;

    public OrderController(CreateOrderService createOrderService) {
        this.createOrderService = createOrderService;
    }

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
                request.userId(),
                request.items().stream()
                        .map(item -> new CreateOrderCommand.Item(item.skuId(), item.quantity()))
                        .toList());
        CreateOrderResult result = createOrderService.create(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new CreateOrderResponse(
                        result.orderId().toString(),
                        result.orderStatus().name(),
                        result.payableAmount(),
                        result.paymentToken()));
    }

    public record CreateOrderResponse(
            String orderId,
            String orderStatus,
            java.math.BigDecimal payableAmount,
            String paymentToken) {
    }
}

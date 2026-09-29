package com.example.orderpayment.application;

import com.example.orderpayment.application.port.OrderPaidEventPublisher;
import com.example.orderpayment.application.port.OrderRepository;
import com.example.orderpayment.application.port.PaymentCallbackRepository;
import com.example.orderpayment.application.port.PaymentSignatureVerifier;
import com.example.orderpayment.domain.model.Order;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentCallbackService {

    private final PaymentSignatureVerifier signatureVerifier;
    private final PaymentCallbackRepository paymentCallbackRepository;
    private final OrderRepository orderRepository;
    private final OrderPaidEventPublisher orderPaidEventPublisher;

    public PaymentCallbackService(
            PaymentSignatureVerifier signatureVerifier,
            PaymentCallbackRepository paymentCallbackRepository,
            OrderRepository orderRepository,
            OrderPaidEventPublisher orderPaidEventPublisher) {
        this.signatureVerifier = signatureVerifier;
        this.paymentCallbackRepository = paymentCallbackRepository;
        this.orderRepository = orderRepository;
        this.orderPaidEventPublisher = orderPaidEventPublisher;
    }

    public PaymentCallbackResult handle(PaymentCallbackCommand command) {
        validate(command);

        if (!signatureVerifier.verify(command.orderId(), command.transactionId(), command.paidAmount(), command.signature())) {
            throw new PaymentSignatureVerificationException();
        }

        if (paymentCallbackRepository.isProcessed(command.transactionId())) {
            return new PaymentCallbackResult(true);
        }

        Order order = orderRepository.findById(command.orderId())
                .orElseThrow(() -> new OrderNotFoundException(command.orderId().toString()));

        order.markPaid();
        paymentCallbackRepository.markProcessed(command.transactionId());
        orderPaidEventPublisher.publish(new OrderPaidEventPublisher.OrderPaidEvent(command.transactionId(), order));

        return new PaymentCallbackResult(true);
    }

    private void validate(PaymentCallbackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.orderId() == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (command.transactionId() == null || command.transactionId().isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (command.paidAmount() == null || command.paidAmount().signum() < 0) {
            throw new IllegalArgumentException("paidAmount must not be negative");
        }
        if (command.signature() == null || command.signature().isBlank()) {
            throw new IllegalArgumentException("signature must not be blank");
        }
    }
}

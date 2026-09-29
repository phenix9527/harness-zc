package com.example.orderpayment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orderpayment.application.port.OrderPaidEventPublisher;
import com.example.orderpayment.application.port.OrderRepository;
import com.example.orderpayment.application.port.PaymentCallbackRepository;
import com.example.orderpayment.application.port.PaymentSignatureVerifier;
import com.example.orderpayment.domain.model.Order;
import com.example.orderpayment.domain.model.OrderItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCallbackServiceTest {

    private final PaymentSignatureVerifier signatureVerifier = org.mockito.Mockito.mock(PaymentSignatureVerifier.class);
    private final PaymentCallbackRepository paymentCallbackRepository = org.mockito.Mockito.mock(PaymentCallbackRepository.class);
    private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    private final OrderPaidEventPublisher orderPaidEventPublisher = org.mockito.Mockito.mock(OrderPaidEventPublisher.class);
    private final PaymentCallbackService service = new PaymentCallbackService(
            signatureVerifier,
            paymentCallbackRepository,
            orderRepository,
            orderPaidEventPublisher);

    @Test
    void handlesPaymentCallbackOnceAndPublishesEventOnce() {
        UUID orderId = UUID.randomUUID();
        PaymentCallbackCommand command = new PaymentCallbackCommand(orderId, "txn-1", new BigDecimal("19.90"), "sig");
        Order order = new Order(orderId, "user-1", List.of(new OrderItem("sku-1", 1, new BigDecimal("19.90"))));

        when(signatureVerifier.verify(orderId, "txn-1", new BigDecimal("19.90"), "sig")).thenReturn(true);
        when(paymentCallbackRepository.isProcessed("txn-1")).thenReturn(false).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentCallbackResult first = service.handle(command);
        PaymentCallbackResult second = service.handle(command);

        assertThat(first.handled()).isTrue();
        assertThat(second.handled()).isTrue();
        assertThat(order.status()).isEqualTo(com.example.orderpayment.domain.model.OrderStatus.PAID);
        verify(orderPaidEventPublisher).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidSignature() {
        UUID orderId = UUID.randomUUID();
        PaymentCallbackCommand command = new PaymentCallbackCommand(orderId, "txn-1", new BigDecimal("19.90"), "bad");

        when(signatureVerifier.verify(orderId, "txn-1", new BigDecimal("19.90"), "bad")).thenReturn(false);

        assertThatThrownBy(() -> service.handle(command))
                .isInstanceOf(PaymentSignatureVerificationException.class);
    }

    @Test
    void rejectsMissingOrder() {
        UUID orderId = UUID.randomUUID();
        PaymentCallbackCommand command = new PaymentCallbackCommand(orderId, "txn-1", new BigDecimal("19.90"), "sig");

        when(signatureVerifier.verify(orderId, "txn-1", new BigDecimal("19.90"), "sig")).thenReturn(true);
        when(paymentCallbackRepository.isProcessed("txn-1")).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(command))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(orderId.toString());
    }
}

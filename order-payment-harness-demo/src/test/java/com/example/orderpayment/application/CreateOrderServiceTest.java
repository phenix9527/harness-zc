package com.example.orderpayment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orderpayment.application.port.InventoryAvailabilityPort;
import com.example.orderpayment.application.port.OrderRepository;
import com.example.orderpayment.application.port.PaymentTokenPort;
import com.example.orderpayment.application.port.ProductCatalogPort;
import com.example.orderpayment.domain.model.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreateOrderServiceTest {

    private final ProductCatalogPort productCatalogPort = org.mockito.Mockito.mock(ProductCatalogPort.class);
    private final InventoryAvailabilityPort inventoryAvailabilityPort = org.mockito.Mockito.mock(InventoryAvailabilityPort.class);
    private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    private final PaymentTokenPort paymentTokenPort = org.mockito.Mockito.mock(PaymentTokenPort.class);
    private final CreateOrderService service = new CreateOrderService(
            productCatalogPort,
            inventoryAvailabilityPort,
            orderRepository,
            paymentTokenPort);

    @Test
    void createsPendingPaymentOrderAndReturnsPaymentToken() {
        CreateOrderCommand command = new CreateOrderCommand(
                "user-1",
                List.of(new CreateOrderCommand.Item("sku-1", 2)));

        when(productCatalogPort.findSellableProduct("sku-1"))
                .thenReturn(Optional.of(new ProductCatalogPort.ProductSnapshot("sku-1", new BigDecimal("12.50"))));
        when(inventoryAvailabilityPort.hasAvailableInventory("sku-1", 2)).thenReturn(true);
        when(paymentTokenPort.createPaymentToken(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenReturn("token-1");

        CreateOrderResult result = service.create(command);

        assertThat(result.orderStatus()).isEqualTo(com.example.orderpayment.domain.model.OrderStatus.PENDING_PAYMENT);
        assertThat(result.payableAmount()).isEqualByComparingTo("25.00");
        assertThat(result.paymentToken()).isEqualTo("token-1");
        verify(orderRepository).save(org.mockito.ArgumentMatchers.any(Order.class));
    }

    @Test
    void rejectsUnknownProduct() {
        CreateOrderCommand command = new CreateOrderCommand(
                "user-1",
                List.of(new CreateOrderCommand.Item("sku-404", 1)));

        when(productCatalogPort.findSellableProduct("sku-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(InvalidProductException.class)
                .hasMessageContaining("sku-404");
    }

    @Test
    void rejectsInsufficientInventory() {
        CreateOrderCommand command = new CreateOrderCommand(
                "user-1",
                List.of(new CreateOrderCommand.Item("sku-1", 3)));

        when(productCatalogPort.findSellableProduct("sku-1"))
                .thenReturn(Optional.of(new ProductCatalogPort.ProductSnapshot("sku-1", new BigDecimal("12.50"))));
        when(inventoryAvailabilityPort.hasAvailableInventory("sku-1", 3)).thenReturn(false);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("sku-1");
    }
}

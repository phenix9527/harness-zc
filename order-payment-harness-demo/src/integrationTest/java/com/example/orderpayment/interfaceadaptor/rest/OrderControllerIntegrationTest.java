package com.example.orderpayment.interfaceadaptor.rest;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderpayment.application.port.InventoryAvailabilityPort;
import com.example.orderpayment.application.port.PaymentTokenPort;
import com.example.orderpayment.application.port.ProductCatalogPort;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_payment_integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void createsOrderSuccessfully() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "items": [
                                    {"skuId": "sku-1", "quantity": 2}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId", matchesPattern("^[0-9a-f\\-]{36}$")))
                .andExpect(jsonPath("$.orderStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payableAmount").value(25.00))
                .andExpect(jsonPath("$.paymentToken").value("stub-token"));
    }

    @Test
    void returnsClearErrorForUnknownProduct() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "items": [
                                    {"skuId": "sku-404", "quantity": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PRODUCT"));
    }

    @Test
    void returnsClearErrorForInsufficientInventory() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "items": [
                                    {"skuId": "sku-2", "quantity": 2}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_INVENTORY"));
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        ProductCatalogPort productCatalogPort() {
            return skuId -> switch (skuId) {
                case "sku-1" -> Optional.of(new ProductCatalogPort.ProductSnapshot("sku-1", new BigDecimal("12.50")));
                case "sku-2" -> Optional.of(new ProductCatalogPort.ProductSnapshot("sku-2", new BigDecimal("8.00")));
                default -> Optional.empty();
            };
        }

        @Bean
        @Primary
        InventoryAvailabilityPort inventoryAvailabilityPort() {
            return (skuId, quantity) -> "sku-1".equals(skuId) && quantity <= 10;
        }

        @Bean
        @Primary
        PaymentTokenPort paymentTokenPort() {
            return order -> "stub-token";
        }
    }
}

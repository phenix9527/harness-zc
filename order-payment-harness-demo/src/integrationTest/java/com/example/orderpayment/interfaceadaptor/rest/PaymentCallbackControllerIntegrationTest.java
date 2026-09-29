package com.example.orderpayment.interfaceadaptor.rest;

import static com.example.orderpayment.infrastructure.InMemoryPaymentSignatureVerifier.sign;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderpayment.infrastructure.InMemoryOrderPaidEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class PaymentCallbackControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    InMemoryOrderPaidEventPublisher eventPublisher;

    @BeforeEach
    void clearEvents() {
        eventPublisher.clear();
    }

    @Test
    void handlesCallbackAndPublishesEventOnce() throws Exception {
        String orderId = createOrder();
        String signature = sign(java.util.UUID.fromString(orderId), "txn-1", new BigDecimal("25.00"));

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "transactionId": "txn-1",
                                  "paidAmount": 25.00,
                                  "signature": "%s"
                                }
                                """.formatted(orderId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handled").value(true));

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "transactionId": "txn-1",
                                  "paidAmount": 25.00,
                                  "signature": "%s"
                                }
                                """.formatted(orderId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handled").value(true));

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "transactionId": "txn-2",
                                  "paidAmount": 25.00,
                                  "signature": "bad"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));

        org.assertj.core.api.Assertions.assertThat(eventPublisher.events()).hasSize(1);
    }

    @Test
    void returnsClearErrorWhenOrderIsMissing() throws Exception {
        String orderId = java.util.UUID.randomUUID().toString();
        String signature = sign(java.util.UUID.fromString(orderId), "txn-9", new BigDecimal("25.00"));

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "transactionId": "txn-9",
                                  "paidAmount": 25.00,
                                  "signature": "%s"
                                }
                                """.formatted(orderId, signature)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }

    private String createOrder() throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "items": [
                                    {"skuId": "sku-1", "quantity": 2}
                                  ]
                                }
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(response);
        return jsonNode.get("orderId").asText();
    }
}

package com.example.orderpayment.application.port;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentSignatureVerifier {

    boolean verify(UUID orderId, String transactionId, BigDecimal paidAmount, String signature);
}

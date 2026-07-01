package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.PaymentSignatureVerifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InMemoryPaymentSignatureVerifier implements PaymentSignatureVerifier {

    private static final String SECRET = "local-test-secret";

    @Override
    public boolean verify(UUID orderId, String transactionId, BigDecimal paidAmount, String signature) {
        String payload = orderId + ":" + transactionId + ":" + paidAmount.toPlainString() + ":" + SECRET;
        return signature.equals(sign(payload));
    }

    public static String sign(UUID orderId, String transactionId, BigDecimal paidAmount) {
        String payload = orderId + ":" + transactionId + ":" + paidAmount.toPlainString() + ":" + SECRET;
        return sign(payload);
    }

    private static String sign(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception exception) {
            throw new IllegalStateException("unable to sign payload", exception);
        }
    }
}

package com.example.orderpayment.application.port;

public interface PaymentCallbackRepository {

    boolean isProcessed(String transactionId);

    void markProcessed(String transactionId);
}

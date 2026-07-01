package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.PaymentCallbackRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPaymentCallbackRepository implements PaymentCallbackRepository {

    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isProcessed(String transactionId) {
        return processedTransactions.contains(transactionId);
    }

    @Override
    public void markProcessed(String transactionId) {
        processedTransactions.add(transactionId);
    }
}

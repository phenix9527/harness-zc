package com.example.orderpayment.application;

public class PaymentSignatureVerificationException extends RuntimeException {

    public PaymentSignatureVerificationException() {
        super("payment signature verification failed");
    }
}

package com.example.orderpayment.interfaceadaptor.rest;

import com.example.orderpayment.application.PaymentCallbackCommand;
import com.example.orderpayment.application.PaymentCallbackRequest;
import com.example.orderpayment.application.PaymentCallbackResult;
import com.example.orderpayment.application.PaymentCallbackService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/callback")
public class PaymentCallbackController {

    private final PaymentCallbackService paymentCallbackService;

    public PaymentCallbackController(PaymentCallbackService paymentCallbackService) {
        this.paymentCallbackService = paymentCallbackService;
    }

    @PostMapping
    public ResponseEntity<PaymentCallbackResponse> handle(@Valid @RequestBody PaymentCallbackRequest request) {
        PaymentCallbackResult result = paymentCallbackService.handle(new PaymentCallbackCommand(
                UUID.fromString(request.orderId()),
                request.transactionId(),
                request.paidAmount(),
                request.signature()));
        return ResponseEntity.status(HttpStatus.OK).body(new PaymentCallbackResponse(result.handled()));
    }

    public record PaymentCallbackResponse(boolean handled) {
    }
}

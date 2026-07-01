package com.example.orderpayment.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank String userId,
        @NotEmpty @Valid List<ItemRequest> items) {

    public record ItemRequest(
            @NotBlank String skuId,
            @Positive int quantity) {
    }
}

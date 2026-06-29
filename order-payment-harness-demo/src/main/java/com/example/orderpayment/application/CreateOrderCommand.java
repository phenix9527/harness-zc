package com.example.orderpayment.application;

import java.util.List;

public record CreateOrderCommand(String userId, List<Item> items) {

    public CreateOrderCommand {
        items = List.copyOf(items);
    }

    public record Item(String skuId, int quantity) {
    }
}

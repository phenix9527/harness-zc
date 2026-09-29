package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.InventoryAvailabilityPort;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class InMemoryInventoryAvailabilityAdapter implements InventoryAvailabilityPort {

    private final Map<String, Integer> inventory = Map.of(
            "sku-1", 100,
            "sku-2", 100,
            "sku-3", 0);

    @Override
    public boolean hasAvailableInventory(String skuId, int quantity) {
        return inventory.getOrDefault(skuId, 0) >= quantity;
    }
}

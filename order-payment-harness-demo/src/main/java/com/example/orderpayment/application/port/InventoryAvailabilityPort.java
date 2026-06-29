package com.example.orderpayment.application.port;

public interface InventoryAvailabilityPort {

    boolean hasAvailableInventory(String skuId, int quantity);
}

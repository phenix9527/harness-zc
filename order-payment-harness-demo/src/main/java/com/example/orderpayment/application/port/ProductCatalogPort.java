package com.example.orderpayment.application.port;

import java.math.BigDecimal;
import java.util.Optional;

public interface ProductCatalogPort {

    Optional<ProductSnapshot> findSellableProduct(String skuId);

    record ProductSnapshot(String skuId, BigDecimal price) {
    }
}

package com.example.orderpayment.infrastructure;

import com.example.orderpayment.application.port.ProductCatalogPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class InMemoryProductCatalogAdapter implements ProductCatalogPort {

    private final Map<String, ProductSnapshot> catalog = Map.of(
            "sku-1", new ProductSnapshot("sku-1", new BigDecimal("12.50")),
            "sku-2", new ProductSnapshot("sku-2", new BigDecimal("8.00")),
            "sku-3", new ProductSnapshot("sku-3", new BigDecimal("19.90")));

    @Override
    public Optional<ProductSnapshot> findSellableProduct(String skuId) {
        return Optional.ofNullable(catalog.get(skuId));
    }
}

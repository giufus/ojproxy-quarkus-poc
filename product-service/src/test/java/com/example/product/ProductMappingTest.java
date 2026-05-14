package com.example.product;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProductMappingTest {

    @Test
    void newProductHoldsAssignedFieldsAndDefersCreatedAt() {
        Product p = new Product();
        p.name = "Widget";
        p.sku = "WID-001";
        p.priceCents = 1999;

        assertEquals("Widget", p.name);
        assertEquals("WID-001", p.sku);
        assertEquals(1999, p.priceCents);
        assertNull(p.createdAt, "createdAt is set by @PrePersist, not by the constructor");
    }
}

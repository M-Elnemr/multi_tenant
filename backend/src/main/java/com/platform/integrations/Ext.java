package com.platform.integrations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What every platform adapter speaks: one neutral shape for products, orders and what the shop sends out. Money is always integer minor units. */
public final class Ext {
    private Ext() {}

    public record Variant(String id, String sku, String barcode, String title, Long priceMinor, Long compareAtMinor, Integer quantity, String inventoryRef) {}

    public record Product(String id, String title, String description, String brand, List<String> images, String status, List<Variant> variants) {}

    public record Item(String sku, String title, int quantity, long unitMinor, long totalMinor, String imageUrl) {}

    /** stage: NEW | PROCESSING | SHIPPED | DELIVERED | CANCELLED | RETURNED */
    public record Order(String id, String number, String status, String stage, long totalMinor, String currency, String customerName, String phone, String email, Map<String, Object> address,
                        List<Item> items, Instant placedAt, String trackingNumber, String carrier) {}

    /** One page of a listing; {@code next} is null at the end. */
    public record Page<T>(List<T> items, String next) {}

    /** A variant of a product the shop wants to publish: the price and quantity are THIS platform's, chosen by the owner. */
    public record VariantDraft(String sku, String barcode, Map<String, String> options, long priceMinor, Long compareAtMinor, int quantity) {}

    public record ProductDraft(String title, String description, String brand, List<String> optionNames, List<String> images, String categoryHint, List<VariantDraft> variants) {}

    /** What the platform answered when a draft was created: ids for each variant, keyed by sku. */
    public record Created(String productId, Map<String, String> variantIds) {}

    /** What the owner can do with an order on a platform (key is sent back to the adapter). */
    public record Action(String key, String label, boolean needsTracking) {}

    public record AccountInfo(String externalId, String label, String currency, Map<String, String> updatedCredentials) {}
}

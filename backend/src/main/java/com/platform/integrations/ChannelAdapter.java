package com.platform.integrations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** One marketplace or store platform. Adapters are stateless: everything they need arrives in the {@link Ctx}. */
public interface ChannelAdapter {
    /** Credentials (decrypted), country, the network, and a way to save refreshed tokens back to the account. */
    record Ctx(Map<String, String> cred, String country, HttpGateway http, String rateKey, Consumer<Map<String, String>> saveCredentials) {
        public String need(String k) {
            String v = cred.get(k);
            if (v == null || v.isBlank()) throw new HttpGateway.ApiException(400, "Missing " + k);
            return v.trim();
        }
        public String opt(String k) { String v = cred.get(k); return v == null || v.isBlank() ? null : v.trim(); }
    }

    String provider();

    /** Checks the credentials against the platform and returns who they belong to. Throws {@link HttpGateway.ApiException} when they are wrong. */
    Ext.AccountInfo connect(Ctx ctx);

    /** One page of the account's products (cursor from the previous page, null for the first). */
    Ext.Page<Ext.Product> listProducts(Ctx ctx, String cursor);

    /** Publishes a new product; throws when the platform refuses it (the message is shown to the owner). */
    Ext.Created createProduct(Ctx ctx, Ext.ProductDraft draft);

    /** Sets this variant's price and/or quantity (null = leave as is). */
    void updateOffer(Ctx ctx, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity);

    Ext.Page<Ext.Order> listOrders(Ctx ctx, Instant since, String cursor);

    /** Actions the owner may take on this order right now (empty when the platform is read-only for orders). */
    default List<Ext.Action> actions(Ext.Order order) { return List.of(); }

    default void act(Ctx ctx, String externalOrderId, String action, Map<String, String> params) { throw new HttpGateway.ApiException(400, "This action is not available for " + provider()); }

    /** Shared by the adapters: price text such as "199.5" -> 19950. */
    static long minor(String amount) {
        if (amount == null || amount.isBlank()) return 0;
        return new java.math.BigDecimal(amount.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    static String amount(long minor) { return java.math.BigDecimal.valueOf(minor, 2).toPlainString(); }
}

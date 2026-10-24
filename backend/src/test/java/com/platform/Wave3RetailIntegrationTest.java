package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.commerce.WebhookService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Purchasing, stock transfers, point of sale with QR codes, invoices, exports and reports, API keys and webhooks, blog, translations, country presets. */
class Wave3RetailIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WebhookService webhooks;
    @Autowired com.platform.commerce.PosService pos;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeHttpGateway.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String branch, String variant, String sku, String productId, String slug, String name, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "Cotton Tee " + uniq(), sku = "SKU-" + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"Soft cotton","variants":[{"sku":"%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(name, sku, branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), branch, JsonPath.read(p, "$.variants[0].id"), sku, JsonPath.read(p, "$.id"), JsonPath.read(p, "$.slug"), name, method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    String order(Shop s, String name, int qty) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"%s","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), qty, s.zones(), name, phone());
        return JsonPath.read(ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated())), "$.orderId");
    }

    void moveOrder(Shop s, String orderId, String... steps) throws Exception {
        for (String st : steps) onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"" + st + "\"}").andExpect(status().isOk());
    }

    long available(Shop s) {
        return jdbc.sql("SELECT coalesce(sum(quantity_on_hand - quantity_reserved), 0) FROM commerce.inventory_items WHERE variant_id = :v").param("v", UUID.fromString(s.variant())).query(Long.class).single();
    }

    // ---- purchasing and transfers ------------------------------------------------------------------------------------------

    @Test
    void purchaseOrdersAddStockAndCostTransfersMoveStockAndNothingIsOversold() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String sup = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/suppliers", "{\"name\":\"Cairo Textiles\",\"phone\":\"01011112222\"}").andExpect(status().isCreated())), "$.id");
        String po = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/purchase-orders", "{\"supplierId\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":30,\"unitCostMinor\":9000}]}".formatted(sup, s.variant())).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor").value(270000)).andExpect(jsonPath("$.status").value("DRAFT"))), "$.id");
        assertThat((String) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/purchase-orders/" + po, null)), "$.number")).matches("PO-\\d{4}-\\d{6}");
        onHost(h, owner, "POST", "/api/v1/store/purchase-orders/" + po + "/ordered", "{}").andExpect(jsonPath("$.status").value("ORDERED"));
        assertThat(available(s)).isEqualTo(50);   // nothing arrives before it is received
        onHost(h, owner, "POST", "/api/v1/store/purchase-orders/" + po + "/receive", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(available(s)).isEqualTo(80);
        assertThat(jdbc.sql("SELECT cost_price_minor FROM commerce.product_variants WHERE id = :v").param("v", UUID.fromString(s.variant())).query(Long.class).single()).isEqualTo(9000L);
        onHost(h, owner, "POST", "/api/v1/store/purchase-orders/" + po + "/receive", "{}").andExpect(status().isConflict());   // never twice
        onHost(h, owner, "POST", "/api/v1/store/purchase-orders/" + po + "/cancel", "{}").andExpect(status().isConflict());

        // a draft built from what is about to run out
        Shop busy = shop();
        order(busy, "Mona Hassan", 20);
        order(busy, "Mona Hassan", 20);
        String draft = ok(onHost(busy.t().host(), busy.t().access(), "POST", "/api/v1/store/purchase-orders/draft-low-stock", "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.items.length()").value(1)));
        assertThat(((Number) JsonPath.read(draft, "$.items[0].quantity")).intValue()).isGreaterThan(40);
        onHost(h, owner, "POST", "/api/v1/store/purchase-orders/draft-low-stock", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_ORDER"));

        // transfers between branches
        String second = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/branches", "{\"name\":\"Giza\",\"code\":\"GZ\",\"address\":\"1 Pyramids Rd\",\"city\":\"Giza\"}").andExpect(status().isCreated())), "$.id");
        onHost(h, owner, "POST", "/api/v1/store/inventory/transfer", "{\"variantId\":\"%s\",\"fromBranch\":\"%s\",\"toBranch\":\"%s\",\"quantity\":10}".formatted(s.variant(), s.branch(), second)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT quantity_on_hand FROM commerce.inventory_items WHERE variant_id = :v AND branch_id = :b").param("v", UUID.fromString(s.variant())).param("b", UUID.fromString(second)).query(Integer.class).single()).isEqualTo(10);
        assertThat(available(s)).isEqualTo(80);   // moved, not lost
        onHost(h, owner, "POST", "/api/v1/store/inventory/transfer", "{\"variantId\":\"%s\",\"fromBranch\":\"%s\",\"toBranch\":\"%s\",\"quantity\":9999}".formatted(s.variant(), s.branch(), second)).andExpect(status().isConflict());
        onHost(h, owner, "POST", "/api/v1/store/inventory/transfer", "{\"variantId\":\"%s\",\"fromBranch\":\"%s\",\"toBranch\":\"%s\",\"quantity\":1}".formatted(s.variant(), s.branch(), s.branch())).andExpect(status().isBadRequest());
        onHost(h, owner, "GET", "/api/v1/store/inventory/transfers", null).andExpect(jsonPath("$.length()").value(1));
        // another shop sees none of it
        onHost(busy.t().host(), busy.t().access(), "GET", "/api/v1/store/purchase-orders/" + po, null).andExpect(status().isNotFound());
        onHost(h, null, "GET", "/api/v1/store/purchase-orders", null).andExpect(status().isUnauthorized());
    }

    // ---- point of sale, invoices -----------------------------------------------------------------------------------------

    @Test
    void theCashierScansAProductLabelSellsAndTheSaleBecomesAnInvoice() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        // the same code works as a typed SKU and as the web address printed in the QR (its v= parameter is the SKU)
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=" + s.sku(), null).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(s.name())).andExpect(jsonPath("$.available").value(50));
        assertThat(pos.lookup(s.id(), "https://" + h + "/products/" + s.slug() + "?v=" + s.sku()).get("variantId").toString()).isEqualTo(s.variant());
        assertThatThrownBy(() -> pos.lookup(s.id(), "https://" + h + "/products/x")).isInstanceOf(com.platform.shared.BusinessException.class);
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=NOPE-1", null).andExpect(status().isNotFound());
        String labels = ok(onHost(h, owner, "GET", "/api/v1/store/pos/labels", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)));
        assertThat((String) JsonPath.read(labels, "$[0].qr")).startsWith("https://" + h + "/products/" + s.slug() + "?v=").endsWith(s.sku());
        assertThat((String) JsonPath.read(labels, "$[0].variantId")).isEqualTo(s.variant());

        String sale = ok(onHost(h, owner, "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":3}],\"paymentMethod\":\"CASH\",\"discountMinor\":1000}".formatted(s.variant())).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor").value(59000)));
        String orderId = JsonPath.read(sale, "$.orderId");
        assertThat((String) JsonPath.read(sale, "$.orderNumber")).startsWith("POS-");
        assertThat(available(s)).isEqualTo(47);
        onHost(h, owner, "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.status").value("ARRIVED")).andExpect(jsonPath("$.paymentStatus").value("PAID")).andExpect(jsonPath("$.source").value("POS"));
        onHost(h, owner, "GET", "/api/v1/store/pos/today", null).andExpect(jsonPath("$.sales").value(1)).andExpect(jsonPath("$.totalMinor").value(59000));
        onHost(h, owner, "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":500}],\"paymentMethod\":\"CASH\"}".formatted(s.variant())).andExpect(status().isConflict());   // cannot sell what is not there
        assertThat(available(s)).isEqualTo(47);
        onHost(h, owner, "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}],\"paymentMethod\":\"GOLD\"}".formatted(s.variant())).andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}],\"paymentMethod\":\"CASH\",\"discountMinor\":999999}".formatted(s.variant())).andExpect(status().isBadRequest());

        // invoice: one per order, numbered, and the same document every time
        String inv = ok(onHost(h, owner, "POST", "/api/v1/store/orders/" + orderId + "/invoice", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("INVOICE")).andExpect(jsonPath("$.totalMinor").value(59000)));
        String number = JsonPath.read(inv, "$.number");
        assertThat(number).matches("INV-\\d{4}-\\d{6}");
        assertThat(((java.util.List<?>) JsonPath.read(inv, "$.lines"))).hasSize(1);
        onHost(h, owner, "POST", "/api/v1/store/orders/" + orderId + "/invoice", "{}").andExpect(jsonPath("$.number").value(number));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + orderId + "/credit-note", "{}").andExpect(status().isConflict());   // the order was not returned

        // a delivered order that comes back gets a credit note that refers to its invoice
        String o2 = order(s, "Nada Ali", 1);
        moveOrder(s, o2, "PREPARING", "SHIPPED", "ARRIVED");
        String inv2 = ok(onHost(h, owner, "POST", "/api/v1/store/orders/" + o2 + "/invoice", "{}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(inv2, "$.number")).isNotEqualTo(number);
        onHost(h, owner, "POST", "/api/v1/store/orders/" + o2 + "/credit-note", "{}").andExpect(status().isConflict());
        moveOrder(s, o2, "RETURNED");
        String cn = ok(onHost(h, owner, "POST", "/api/v1/store/orders/" + o2 + "/credit-note", "{\"reason\":\"Customer returned it\"}").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("CREDIT_NOTE")));
        assertThat((String) JsonPath.read(cn, "$.number")).matches("CN-\\d{4}-\\d{6}");
        assertThat((String) JsonPath.read(cn, "$.relatedInvoiceId")).isEqualTo(JsonPath.read(inv2, "$.id"));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + o2 + "/credit-note", "{}").andExpect(jsonPath("$.number").value((String) JsonPath.read(cn, "$.number")));
        onHost(h, owner, "GET", "/api/v1/store/orders/" + o2 + "/invoices", null).andExpect(jsonPath("$.length()").value(2));
        onHost(h, owner, "GET", "/api/v1/store/invoices", null).andExpect(jsonPath("$.meta.total").value(3));

        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/orders/" + orderId + "/invoice", "{}").andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/pos/lookup?code=" + s.sku(), null).andExpect(status().isNotFound());
    }

    // ---- exports and reports -----------------------------------------------------------------------------------------------

    @Test
    void exportsAreSafeForSpreadsheetsAndReportsShowRealMargin() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        order(s, "=HYPERLINK(1)", 2);
        String csv = ok(onHost(h, owner, "GET", "/api/v1/store/export/orders.csv", null).andExpect(status().isOk()));
        assertThat(csv).startsWith("﻿\"order_number\"".substring(0, 1) + "order_number").contains("ST-").contains("'=HYPERLINK").doesNotContain(",\"=HYPERLINK");
        assertThat(ok(onHost(h, owner, "GET", "/api/v1/store/export/customers.csv", null))).contains("phone,name,orders");
        onHost(h, null, "GET", "/api/v1/store/export/orders.csv", null).andExpect(status().isUnauthorized());

        jdbc.sql("UPDATE commerce.product_variants SET cost_price_minor = 12000 WHERE id = :v").param("v", UUID.fromString(s.variant())).update();
        String o = order(s, "Mona Hassan", 3);
        moveOrder(s, o, "PREPARING", "SHIPPED", "ARRIVED");
        String m = ok(onHost(h, owner, "GET", "/api/v1/store/reports/margin?days=30", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(m, "$.totalRevenueMinor")).longValue()).isEqualTo(100000L);   // 5 pieces at 200.00 across both orders
        assertThat(((Number) JsonPath.read(m, "$.totalMarginMinor")).longValue()).isEqualTo(40000L);      // cost 120.00 each
        assertThat(((Number) JsonPath.read(m, "$.marginPercent")).doubleValue()).isEqualTo(40.0);
        onHost(h, owner, "GET", "/api/v1/store/reports/cohorts", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].customers").value(2));
    }

    // ---- public API keys and webhooks ---------------------------------------------------------------------------------------

    @Test
    void apiKeysAreReadOnlyAndPerShopAndWebhooksAreSignedAndRetried() throws Exception {
        Shop s = shop(), other = shop();
        String h = s.t().host(), owner = s.t().access();
        String created = ok(onHost(h, owner, "POST", "/api/v1/store/api-keys", "{\"name\":\"My ERP\"}").andExpect(status().isCreated()));
        String key = JsonPath.read(created, "$.key"), keyId = JsonPath.read(created, "$.id");
        assertThat(key).startsWith("mk_live_");
        assertThat(ok(onHost(h, owner, "GET", "/api/v1/store/api-keys", null))).doesNotContain(key);   // shown once
        String o = order(s, "Mona Hassan", 1);
        String num = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/orders/" + o, null)), "$.orderNumber");
        onApi(h, key, "/api/v1/public/orders").andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderNumber").value(num));
        onApi(h, key, "/api/v1/public/orders/" + num).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sku").value(s.sku()));
        onApi(h, key, "/api/v1/public/products").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        onApi(h, null, "/api/v1/public/orders").andExpect(status().isUnauthorized());
        onApi(other.t().host(), key, "/api/v1/public/orders").andExpect(status().isUnauthorized());   // a key opens only its own shop
        onApi(h, key, "/api/v1/store/orders").andExpect(status().isUnauthorized());                    // and only the public API
        onHost(h, owner, "DELETE", "/api/v1/store/api-keys/" + keyId, null).andExpect(status().isNoContent());
        onApi(h, key, "/api/v1/public/orders").andExpect(status().isUnauthorized());

        // webhooks: https only, public addresses only, secret shown once
        onHost(h, owner, "POST", "/api/v1/store/webhooks", "{\"url\":\"http://example.com/hook\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/webhooks", "{\"url\":\"https://127.0.0.1/hook\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/webhooks", "{\"url\":\"https://example.com/hook\",\"events\":[\"nope\"]}").andExpect(status().isBadRequest());
        String hook = ok(onHost(h, owner, "POST", "/api/v1/store/webhooks", "{\"url\":\"https://example.com/hook\"}").andExpect(status().isCreated()));
        String secret = JsonPath.read(hook, "$.secret"), hookId = JsonPath.read(hook, "$.id");
        assertThat(ok(onHost(h, owner, "GET", "/api/v1/store/webhooks", null))).doesNotContain(secret);

        FakeHttpGateway.when("POST", "example.com/hook", 200, "ok");
        order(s, "Nada Ali", 1);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.webhook_deliveries WHERE webhook_id = :w AND status = 'PENDING'").param("w", UUID.fromString(hookId)).query(Long.class).single()).isEqualTo(1);
        assertThat(webhooks.deliverDue(Instant.now())).isGreaterThanOrEqualTo(1);
        var call = FakeHttpGateway.calls("POST", "example.com/hook").get(0);
        assertThat(call.headers().get("X-Webhook-Event")).isEqualTo("order.created");
        assertThat(call.headers().get("X-Webhook-Signature")).isEqualTo(hmac(secret, call.body()));
        assertThat(call.body()).contains("\"event\":\"order.created\"").contains(s.sku());
        onHost(h, owner, "GET", "/api/v1/store/webhooks/" + hookId + "/deliveries", null).andExpect(jsonPath("$[0].status").value("SENT"));

        // a failing receiver is retried with growing delays, then given up on
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "example.com/hook", 500, "boom");
        order(s, "Sara Ahmed", 1);
        Instant t0 = Instant.now();
        webhooks.deliverDue(t0);
        assertThat(jdbc.sql("SELECT attempts FROM commerce.webhook_deliveries WHERE webhook_id = :w AND status = 'PENDING'").param("w", UUID.fromString(hookId)).query(Integer.class).single()).isEqualTo(1);
        webhooks.deliverDue(t0.plusSeconds(10));   // too early: not retried yet
        assertThat(jdbc.sql("SELECT attempts FROM commerce.webhook_deliveries WHERE webhook_id = :w AND status = 'PENDING'").param("w", UUID.fromString(hookId)).query(Integer.class).single()).isEqualTo(1);
        for (int i = 1; i <= 8; i++) webhooks.deliverDue(t0.plusSeconds(i * 100_000L));
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.webhook_deliveries WHERE webhook_id = :w AND status = 'FAILED'").param("w", UUID.fromString(hookId)).query(Long.class).single()).isEqualTo(1);
        onHost(other.t().host(), other.t().access(), "DELETE", "/api/v1/store/webhooks/" + hookId, null).andExpect(status().isNotFound());
        onHost(h, owner, "DELETE", "/api/v1/store/webhooks/" + hookId, null).andExpect(status().isNoContent());
    }

    ResultActions onApi(String host, String key, String path) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Host", host);
        if (key != null) req = req.header("X-Api-Key", key);
        return mvc.perform(req);
    }

    static String hmac(String secret, String body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder b = new StringBuilder("sha256=");
        for (byte x : mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
        return b.toString();
    }

    // ---- blog, translations, country preset -----------------------------------------------------------------------------

    @Test
    void theBlogKeepsDraftsPrivateAndEnglishNamesShowOnlyToEnglishReaders() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String post = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/blog", "{\"title\":\"How to pick a size\",\"body\":\"Measure first.\\n\\nThen compare.\",\"published\":false}").andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("how-to-pick-a-size"))), "$.id");
        onHost(h, null, "GET", "/api/v1/shop/blog", null).andExpect(jsonPath("$.length()").value(0));
        onHost(h, null, "GET", "/api/v1/shop/blog/how-to-pick-a-size", null).andExpect(status().isNotFound());
        onHost(h, owner, "POST", "/api/v1/store/blog", "{\"title\":\"Other\",\"slug\":\"how-to-pick-a-size\"}").andExpect(status().isConflict());
        onHost(h, owner, "PUT", "/api/v1/store/blog/" + post, "{\"title\":\"How to pick a size\",\"body\":\"Measure first.\",\"published\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));
        onHost(h, null, "GET", "/api/v1/shop/blog", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("How to pick a size"));
        onHost(h, null, "GET", "/api/v1/shop/blog/how-to-pick-a-size", null).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Measure first."));
        Shop other = shop();
        onHost(other.t().host(), null, "GET", "/api/v1/shop/blog/how-to-pick-a-size", null).andExpect(status().isNotFound());
        onHost(h, owner, "DELETE", "/api/v1/store/blog/" + post, null).andExpect(status().isNoContent());

        // English text: shown with lang=en, never otherwise
        onHost(h, owner, "PUT", "/api/v1/store/translations/" + s.productId(), "{\"name\":\"Cotton T-shirt\",\"description\":\"Soft cotton\"}").andExpect(status().isOk());
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slug() + "?lang=en", null).andExpect(jsonPath("$.name").value("Cotton T-shirt"));
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slug(), null).andExpect(jsonPath("$.name").value(s.name()));
        onHost(h, null, "GET", "/api/v1/shop/products?lang=en", null).andExpect(jsonPath("$.data[0].name").value("Cotton T-shirt"));
        onHost(h, null, "GET", "/api/v1/shop/products", null).andExpect(jsonPath("$.data[0].name").value(s.name()));
        onHost(h, owner, "PUT", "/api/v1/store/translations/" + s.productId(), "{\"name\":\"x\"}").andExpect(status().isBadRequest());

        // the AI drafts English for products that have none; the owner can edit it afterwards
        Shop fresh = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"name\":\"Cotton Tee (draft)\",\"description\":\"Soft cotton\"}"));
        onHost(fresh.t().host(), fresh.t().access(), "POST", "/api/v1/store/translations/draft", "{}").andExpect(jsonPath("$.drafted").value(1));
        onHost(fresh.t().host(), null, "GET", "/api/v1/shop/products/" + fresh.slug() + "?lang=en", null).andExpect(jsonPath("$.name").value("Cotton Tee (draft)"));
    }

    @Test
    void aCountryPresetSetsCurrencyVatAndTimezone() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "PUT", "/api/v1/store/market", "{\"country\":\"SA\"}").andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("SAR")).andExpect(jsonPath("$.timezone").value("Asia/Riyadh")).andExpect(jsonPath("$.vatPercent").value(15.0)).andExpect(jsonPath("$.country").value("SA"));
        onHost(h, owner, "PUT", "/api/v1/store/market", "{\"country\":\"AE\"}").andExpect(jsonPath("$.currency").value("AED")).andExpect(jsonPath("$.vatPercent").value(5.0));
        onHost(h, owner, "PUT", "/api/v1/store/market", "{\"country\":\"XX\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "GET", "/api/v1/store/market", null).andExpect(jsonPath("$.presets.length()").value(3));
    }
}

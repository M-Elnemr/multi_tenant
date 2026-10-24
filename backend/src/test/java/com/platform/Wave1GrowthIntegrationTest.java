package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.commerce.CartRecoveryService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** COD risk guard (score, hold, confirm) and incomplete carts (capture, 24 h list, recovery, free-only reminders). Meta is faked. */
class Wave1GrowthIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired CartRecoveryService carts;

    @BeforeEach
    void clean() { FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zonesMethod) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","variants":[{"sku":"T-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String variant = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.variants[0].id");
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), variant, method);
    }

    String cart(Shop s, String phone, String name, String address, int qty) {
        return """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"%s","phone":"%s","addressLine1":"%s","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), qty, s.zonesMethod(), name, phone, address);
    }

    String place(Shop s, String phone, String name, String address, int qty) throws Exception {
        return ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", cart(s, phone, name, address, qty), "k-" + uniq()).andExpect(status().isCreated()));
    }

    // ---- COD guard -------------------------------------------------------------------------------------------------------------

    @Test
    void everyCodOrderIsScoredAndRiskyOnesCanBeHeldUntilTheOwnerConfirms() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String good = "010" + (10_000_000L + System.nanoTime() % 80_000_000L);
        String id = JsonPath.read(place(s, good, "Mona Hassan", "12 Tahrir Street, Dokki, floor 3", 1), "$.orderId");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + id, null).andExpect(jsonPath("$.riskLevel").value("LOW")).andExpect(jsonPath("$.needsConfirmation").value(false));

        // a phone that sent goods back twice, with a thin address: high risk, but not held until the owner asks for it
        String bad = "010" + (10_000_000L + (System.nanoTime() + 7) % 80_000_000L);
        jdbc.sql("INSERT INTO commerce.phone_flags (tenant_id, phone, returned_count) VALUES (:t, :p, 2)").param("t", s.id()).param("p", "+2" + bad).update();
        String risky = JsonPath.read(place(s, bad, "Ali", "Cairo", 1), "$.orderId");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + risky, null).andExpect(jsonPath("$.riskLevel").value("HIGH")).andExpect(jsonPath("$.riskReasons").value(org.hamcrest.Matchers.containsString("RETURNED_BEFORE")))
                .andExpect(jsonPath("$.needsConfirmation").value(false));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + risky + "/status", "{\"status\":\"PREPARING\"}").andExpect(status().isOk());

        // switch holding on: the next risky order cannot be prepared or shipped until confirmed
        onHost(h, owner, "PUT", "/api/v1/store/cod-guard", "{\"holdHighRisk\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.holdHighRisk").value(true));
        String held = JsonPath.read(place(s, bad, "Ali", "Cairo", 1), "$.orderId");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + held, null).andExpect(jsonPath("$.needsConfirmation").value(true));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + held + "/status", "{\"status\":\"PREPARING\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NEEDS_CONFIRMATION"));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + held + "/confirm", "{}").andExpect(status().isOk());
        onHost(h, owner, "POST", "/api/v1/store/orders/" + held + "/status", "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        // a good customer is never held
        String fine = JsonPath.read(place(s, good, "Mona Hassan", "12 Tahrir Street, Dokki, floor 3", 1), "$.orderId");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + fine, null).andExpect(jsonPath("$.needsConfirmation").value(false));

        onHost(h, owner, "GET", "/api/v1/store/cod-guard", null).andExpect(jsonPath("$.high").value(2)).andExpect(jsonPath("$.settings.holdHighRisk").value(true));
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/cod-guard", null).andExpect(jsonPath("$.high").value(0)).andExpect(jsonPath("$.settings.holdHighRisk").value(false));
        onHost(h, null, "GET", "/api/v1/store/cod-guard", null).andExpect(status().isUnauthorized());
    }

    // ---- incomplete carts ----------------------------------------------------------------------------------------------------------

    String snapshot(Shop s, String key, String phone, int qty) throws Exception {
        return ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"%s\",\"name\":\"Nada Ali\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":%d}]}".formatted(key, phone, s.variant(), qty)).andExpect(status().isNoContent()));
    }

    void connectWhatsApp(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted("7" + System.nanoTime())).andExpect(status().isCreated());
    }

    @Test
    void anIncompleteCartShowsAfter24HoursIsRecoveredByAnOrderAndRemindersNeverCostMoneyUnlessAllowed() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String phone = "010" + (10_000_000L + System.nanoTime() % 80_000_000L);
        String key = "cart_" + uniq();
        snapshot(s, key, phone, 2);
        snapshot(s, key, phone, 3);   // the same cart edited: still one row, created time kept
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.abandoned_carts WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(1);
        onHost(h, owner, "GET", "/api/v1/store/carts", null).andExpect(jsonPath("$.carts.length()").value(0)).andExpect(jsonPath("$.stats.recentCount").value(1));
        onHost(h, owner, "GET", "/api/v1/store/carts?view=recent", null).andExpect(jsonPath("$.carts[0].itemCount").value(3)).andExpect(jsonPath("$.carts[0].totalMinor").value(60000));

        jdbc.sql("UPDATE commerce.abandoned_carts SET created_at = now() - interval '25 hours' WHERE tenant_id = :t").param("t", s.id()).update();
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/carts", null).andExpect(jsonPath("$.carts.length()").value(1)).andExpect(jsonPath("$.stats.dueCount").value(1)).andExpect(jsonPath("$.stats.dueMinor").value(60000)));
        assertThat((String) JsonPath.read(list, "$.carts[0].whatsappUrl")).startsWith("https://wa.me/2" + phone).contains("text=");
        assertThat((String) JsonPath.read(list, "$.carts[0].items[0].name")).startsWith("Tee ");

        // no WhatsApp channel: the reminder says so; nothing is charged
        String cartId = JsonPath.read(list, "$.carts[0].id");
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEND_FAILED"));

        // with WhatsApp connected but no message from the customer in 24 h, a free message is impossible and paid ones are off
        connectWhatsApp(s);
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
        // paid allowed but the template is not approved: still nothing is sent or charged
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"allowPaid\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.allowPaid").value(true));
        long before = jdbc.sql("SELECT coalesce(balance_minor, 0) FROM billing.wallets WHERE tenant_id = :t").param("t", s.id()).query(Long.class).optional().orElse(0L);
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_APPROVED"));
        assertThat(jdbc.sql("SELECT coalesce(balance_minor, 0) FROM billing.wallets WHERE tenant_id = :t").param("t", s.id()).query(Long.class).optional().orElse(0L)).isEqualTo(before);
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> "text".equals(x.body().get("type")) || "template".equals(x.body().get("type"))).count()).isZero();
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"allowPaid\":false}").andExpect(jsonPath("$.allowPaid").value(false));

        // the customer wrote to the shop recently: now the reminder is a normal free message, and the scheduler does the same once
        jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now()) ON CONFLICT (tenant_id, number) DO UPDATE SET last_inbound_at = now()").param("t", s.id()).param("n", "2" + phone).update();
        carts.runDue(Instant.now());
        assertThat(jdbc.sql("SELECT reminder_status FROM commerce.abandoned_carts WHERE id = :i").param("i", UUID.fromString(cartId)).query(String.class).single()).isEqualTo("SENT_FREE");
        carts.runDue(Instant.now());
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> ("2" + phone).equals(x.body().get("to"))).count()).isEqualTo(1);   // one reminder per cart, ever
        assertThat(FakeMetaGraph.SENT.stream().anyMatch(x -> "text".equals(x.body().get("type")) && String.valueOf(x.body()).contains("/cart"))).isTrue();

        // the customer orders: the cart is recovered and counted
        place(s, phone, "Nada Ali", "5 Tahrir Street, Dokki, floor 2", 3);
        onHost(h, owner, "GET", "/api/v1/store/carts?view=recovered", null).andExpect(jsonPath("$.carts.length()").value(1)).andExpect(jsonPath("$.stats.recoveredCount").value(1)).andExpect(jsonPath("$.stats.recoveredMinor").value(60000))
                .andExpect(jsonPath("$.stats.recoveryRatePercent").value(100.0));
        onHost(h, owner, "GET", "/api/v1/store/carts", null).andExpect(jsonPath("$.carts.length()").value(0));

        // validation, dismissing, isolation
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"couponCode\":\"NOPE\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"remindAfterHours\":0}").andExpect(status().isBadRequest());
        String k2 = "cart_" + uniq();
        snapshot(s, k2, "010" + (10_000_000L + (System.nanoTime() + 3) % 80_000_000L), 1);
        jdbc.sql("UPDATE commerce.abandoned_carts SET created_at = now() - interval '30 hours' WHERE client_key = :k").param("k", k2).update();
        String c2 = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts", null)), "$.carts[0].id");
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/carts/" + c2 + "/dismiss", "{}").andExpect(status().isNotFound());
        onHost(h, owner, "POST", "/api/v1/store/carts/" + c2 + "/dismiss", "{}").andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/carts", null).andExpect(jsonPath("$.carts.length()").value(0));
        // an unusable phone or an empty cart records nothing
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"%s\",\"phone\":\"123\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted("cart_" + uniq(), s.variant())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.abandoned_carts WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(2);
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"bad key!\",\"phone\":\"01011112222\",\"items\":[]}").andExpect(status().isBadRequest());
    }
}

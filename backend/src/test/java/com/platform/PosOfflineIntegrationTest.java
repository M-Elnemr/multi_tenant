package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The till with no internet: a terminal's queued actions applied once each, in order, never refusing a sale that was paid; what does not add up becomes a sync issue. */
class PosOfflineIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    record Setup(Tenant t, UUID tenantId, String token, UUID owner, String branch, String shirt, String cap) {}

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    Setup setup() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String shirt = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"قميص كتان","variants":[{"sku":"SH-%s","barcode":"622%s","priceMinor":30000,"optionValues":{},"stock":[{"branchId":"%s","quantity":2}]}]}
                """.formatted(uniq(), uniq().replaceAll("[^0-9]", "1"), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String cap = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"كاب","variants":[{"sku":"CP-%s","priceMinor":10000,"optionValues":{},"stock":[{"branchId":"%s","quantity":20}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String reg = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/pos/devices", "{\"name\":\"الكاشير ١\"}").andExpect(status().isCreated()));
        UUID owner = jdbc.sql("SELECT user_id FROM core.user_tenant_memberships WHERE tenant_id = :t LIMIT 1").param("t", id).query(UUID.class).single();
        return new Setup(t, id, JsonPath.read(reg, "$.token"), owner, branch, shirt, cap);
    }

    ResultActions device(Setup s, String method, String path, String json) throws Exception {
        var b = "POST".equals(method) ? post(path) : get(path);
        b.header("Host", s.t().host()).header("X-Pos-Device", s.token());
        if (json != null) b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b);
    }

    static Map<String, Object> op(long seq, String type, UUID cashier, Map<String, Object> payload) {
        return Map.of("opId", UUID.randomUUID().toString(), "seq", seq, "type", type, "at", java.time.OffsetDateTime.now().toString(), "cashierId", cashier.toString(), "payload", payload);
    }

    String push(Setup s, List<Map<String, Object>> ops) throws Exception {
        return body(device(s, "POST", "/api/v1/store/pos/sync/push", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("ops", ops))).andExpect(status().isOk()));
    }

    static Map<String, Object> sale(UUID orderId, String number, UUID shift, String variant, int qty, long unit, List<Map<String, Object>> payments) {
        return Map.of("orderId", orderId.toString(), "number", number, "shiftId", shift.toString(), "offline", true,
                "items", List.of(Map.of("variantId", variant, "qty", qty, "unitMinor", unit)), "payments", payments);
    }

    @Test
    void aTerminalGetsItsCatalogAndARevokedOneGetsNothing() throws Exception {
        Setup s = setup();
        String boot = body(device(s, "GET", "/api/v1/store/pos/terminal/bootstrap", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(boot, "$.catalog[*].variantId")).contains(s.shirt(), s.cap());
        assertThat(JsonPath.<String>read(boot, "$.device.code")).isEqualTo("A1");
        // the cap was added without a barcode: it got the shop's own EAN-13, so it can be scanned and printed on a label
        String capCode = JsonPath.<List<String>>read(boot, "$.catalog[?(@.variantId=='" + s.cap() + "')].barcode").get(0);
        assertThat(capCode).startsWith("20").hasSize(13);
        assertThat(com.platform.commerce.Barcodes.isEan13(capCode)).isTrue();
        assertThat(JsonPath.<List<String>>read(boot, "$.cashiers[*].userId")).contains(s.owner().toString());
        // without a terminal token the terminal's endpoints are closed, and a staff session is no substitute
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/pos/terminal/bootstrap", null).andExpect(status().isForbidden());
        String dev = JsonPath.read(boot, "$.device.id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/pos/devices/" + dev + "/revoke", null).andExpect(status().isOk());
        device(s, "GET", "/api/v1/store/pos/terminal/bootstrap", null).andExpect(status().isUnauthorized());
    }

    @Test
    void theSameSaleSentTwiceMakesOneOrderAndAnOversellIsKeptAndFlagged() throws Exception {
        Setup s = setup();
        UUID shift = UUID.randomUUID(), order = UUID.randomUUID();
        var open = op(1, "SHIFT_OPEN", s.owner(), Map.of("shiftId", shift.toString(), "openingFloatMinor", 50000));
        // 3 shirts sold offline, 2 on the shelf: the sale is recorded in full, stock goes to 0, one issue for the missing piece
        var sell = op(2, "SALE", s.owner(), sale(order, "POS-A1-000001", shift, s.shirt(), 3, 30000, List.of(Map.of("method", "CASH", "amountMinor", 90000))));
        String r1 = push(s, List.of(open, sell));
        assertThat(JsonPath.<List<String>>read(r1, "$.results[*].status")).containsExactly("APPLIED", "FLAGGED");
        String r2 = push(s, List.of(sell));   // the terminal never got the answer and sends it again
        assertThat(JsonPath.<Boolean>read(r2, "$.results[0].duplicate")).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE id = :o").param("o", order).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT order_number FROM commerce.orders WHERE id = :o").param("o", order).query(String.class).single()).isEqualTo("POS-A1-000001");
        assertThat(jdbc.sql("SELECT total_minor FROM commerce.orders WHERE id = :o").param("o", order).query(Long.class).single()).isEqualTo(90000);
        assertThat(jdbc.sql("SELECT quantity_on_hand FROM commerce.inventory_items WHERE variant_id = CAST(:v AS uuid)").param("v", s.shirt()).query(Integer.class).single()).isZero();
        String issues = body(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/pos/issues", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(issues, "$[*].kind")).containsExactly("NEGATIVE_STOCK");
    }

    @Test
    void aPriceChangedMeanwhileKeepsWhatTheCustomerPaidAndActionsAreAppliedInTheirOrder() throws Exception {
        Setup s = setup();
        UUID shift = UUID.randomUUID(), order = UUID.randomUUID();
        jdbc.sql("UPDATE commerce.product_variants SET price_minor = 12000 WHERE id = CAST(:v AS uuid)").param("v", s.cap()).update();
        // sent out of order: the shift (seq 1) is applied before the sale (seq 2) whatever order they arrive in
        var sell = op(2, "SALE", s.owner(), sale(order, "POS-A1-000002", shift, s.cap(), 2, 10000, List.of(Map.of("method", "CARD_POS", "amountMinor", 5000), Map.of("method", "CASH", "amountMinor", 15000))));
        var open = op(1, "SHIFT_OPEN", s.owner(), Map.of("shiftId", shift.toString(), "openingFloatMinor", 0));
        String r = push(s, List.of(sell, open));
        assertThat(JsonPath.<List<String>>read(r, "$.results[*].status")).containsExactly("APPLIED", "FLAGGED");
        assertThat(jdbc.sql("SELECT total_minor FROM commerce.orders WHERE id = :o").param("o", order).query(Long.class).single()).isEqualTo(20000);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.order_payments WHERE order_id = :o AND kind = 'PAYMENT'").param("o", order).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT kind FROM commerce.pos_sync_issues WHERE tenant_id = :t").param("t", s.tenantId()).query(String.class).list()).containsExactly("PRICE_CHANGED");
    }

    @Test
    void returnsNeverGiveBackMoreThanWasSoldAndTheDrawerAddsUpAtHandover() throws Exception {
        Setup s = setup();
        UUID shift = UUID.randomUUID(), order = UUID.randomUUID();
        String rid1 = UUID.randomUUID().toString();
        String r = push(s, List.of(
                op(1, "SHIFT_OPEN", s.owner(), Map.of("shiftId", shift.toString(), "openingFloatMinor", 20000)),
                op(2, "SALE", s.owner(), sale(order, "POS-A1-000003", shift, s.cap(), 3, 10000, List.of(Map.of("method", "CASH", "amountMinor", 30000)))),
                Map.of("opId", rid1, "seq", 3, "type", "RETURN", "at", java.time.OffsetDateTime.now().toString(), "cashierId", s.owner().toString(),
                        "payload", Map.of("orderId", order.toString(), "shiftId", shift.toString(), "refundMethod", "CASH", "items", List.of(Map.of("variantId", s.cap(), "qty", 1)))),
                // a second terminal had the same sale and returned 3 more: only 2 were left
                op(4, "RETURN", s.owner(), Map.of("orderId", order.toString(), "shiftId", shift.toString(), "refundMethod", "CASH", "items", List.of(Map.of("variantId", s.cap(), "qty", 3)))),
                op(5, "CASH_MOVE", s.owner(), Map.of("moveId", UUID.randomUUID().toString(), "shiftId", shift.toString(), "kind", "PAY_OUT", "amountMinor", 5000, "reason", "شاي")),
                // 200 float + 300 cash − 100 − 200 refunds − 50 out = 150 expected; the cashier counted 140
                op(6, "SHIFT_CLOSE", s.owner(), Map.of("shiftId", shift.toString(), "countedMinor", 14000, "expectedMinor", 15000, "handoverKind", "SAFE"))));
        assertThat(JsonPath.<List<String>>read(r, "$.results[*].status")).containsExactly("APPLIED", "APPLIED", "APPLIED", "FLAGGED", "APPLIED", "APPLIED");
        assertThat(jdbc.sql("SELECT coalesce(sum(ri.quantity), 0) FROM commerce.order_return_items ri JOIN commerce.order_returns rr ON rr.id = ri.return_id WHERE rr.order_id = :o").param("o", order).query(Long.class).single()).isEqualTo(3);
        var shiftRow = jdbc.sql("SELECT expected_cash_minor, difference_minor, status FROM commerce.pos_shifts WHERE id = :s").param("s", shift).query().singleRow();
        assertThat(((Number) shiftRow.get("expected_cash_minor")).longValue()).isEqualTo(15000);
        assertThat(((Number) shiftRow.get("difference_minor")).longValue()).isEqualTo(-1000);
        assertThat(shiftRow.get("status")).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT quantity_on_hand FROM commerce.inventory_items WHERE variant_id = CAST(:v AS uuid)").param("v", s.cap()).query(Integer.class).single()).isEqualTo(20);   // 20 − 3 + 3
        // the same return sent again applies nothing
        String again = push(s, List.of(Map.of("opId", rid1, "seq", 3, "type", "RETURN", "at", java.time.OffsetDateTime.now().toString(), "cashierId", s.owner().toString(),
                "payload", Map.of("orderId", order.toString(), "shiftId", shift.toString(), "refundMethod", "CASH", "items", List.of(Map.of("variantId", s.cap(), "qty", 1))))));
        assertThat(JsonPath.<Boolean>read(again, "$.results[0].duplicate")).isTrue();
        String day = body(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/pos/summary", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(day, "$.byHour")).hasSize(1);
        assertThat(JsonPath.<Number>read(day, "$.byHour[0].totalMinor").longValue()).isEqualTo(30000);
        String report = body(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/pos/shifts/" + shift, null).andExpect(status().isOk()));
        assertThat(JsonPath.<Number>read(report, "$.totals.totalMinor").longValue()).isEqualTo(30000);
    }

    @Test
    void aCountAppliedLaterKeepsTheSalesMadeAfterIt() throws Exception {
        Setup s = setup();
        UUID shift = UUID.randomUUID(), count = UUID.randomUUID();
        // counted 18 caps when 20 were expected (2 missing); then 1 sold; applying the count must leave 17, not 18
        push(s, List.of(
                op(1, "COUNT", s.owner(), Map.of("countId", count.toString(), "name", "جرد", "status", "REVIEW", "lines", List.of(Map.of("variantId", s.cap(), "expected", 20, "counted", 18)))),
                op(2, "SHIFT_OPEN", s.owner(), Map.of("shiftId", shift.toString(), "openingFloatMinor", 0)),
                op(3, "SALE", s.owner(), sale(UUID.randomUUID(), "POS-A1-000004", shift, s.cap(), 1, 10000, List.of(Map.of("method", "CASH", "amountMinor", 10000))))));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/pos/counts/" + count + "/apply", null).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT quantity_on_hand FROM commerce.inventory_items WHERE variant_id = CAST(:v AS uuid)").param("v", s.cap()).query(Integer.class).single()).isEqualTo(17);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/pos/counts/" + count + "/apply", null).andExpect(status().isConflict());
    }

    @Test
    void anActionThatCannotBeAppliedIsKeptAsAnIssueAndNotRetried() throws Exception {
        Setup s = setup();
        var bad = op(1, "SALE", s.owner(), sale(UUID.randomUUID(), "POS-A1-000009", UUID.randomUUID(), s.cap(), 1, 10000, List.of()));   // its shift never reached the server
        String r = push(s, List.of(bad));
        assertThat(JsonPath.<String>read(r, "$.results[0].status")).isEqualTo("FAILED");
        assertThat(JsonPath.<String>read(push(s, List.of(bad)), "$.results[0].status")).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.pos_sync_issues WHERE tenant_id = :t AND kind = 'FAILED'").param("t", s.tenantId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aCashierPinIsUniqueAndTheTerminalGetsOnlyItsHash() throws Exception {
        Setup s = setup();
        onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/pos/team/" + s.owner() + "/pin", "{\"pin\":\"1234\"}").andExpect(status().isOk());
        onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/pos/team/" + s.owner() + "/pin", "{\"pin\":\"12\"}").andExpect(status().isBadRequest());
        String boot = body(device(s, "GET", "/api/v1/store/pos/terminal/bootstrap", null).andExpect(status().isOk()));
        assertThat(boot).doesNotContain("\"1234\"");
        String salt = JsonPath.<List<String>>read(boot, "$.cashiers[?(@.userId=='" + s.owner() + "')].salt").get(0);
        String hash = JsonPath.<List<String>>read(boot, "$.cashiers[?(@.userId=='" + s.owner() + "')].pinHash").get(0);
        var spec = new javax.crypto.spec.PBEKeySpec("1234".toCharArray(), java.util.HexFormat.of().parseHex(salt), 60_000, 256);
        assertThat(java.util.HexFormat.of().formatHex(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded())).isEqualTo(hash);
    }

    @Test
    void aWholeSaleDiscountIsSharedOverTheLinesSoAReturnGivesBackWhatWasPaid() throws Exception {
        Setup s = setup();
        UUID shift = UUID.randomUUID(), order = UUID.randomUUID();
        // 1 shirt (300) + 2 caps (200) = 500, 50 off the whole sale: the shirt line carries 30 of it, the caps 20; returning one cap gives back 90
        push(s, List.of(
                op(1, "SHIFT_OPEN", s.owner(), Map.of("shiftId", shift.toString(), "openingFloatMinor", 0)),
                op(2, "SALE", s.owner(), Map.of("orderId", order.toString(), "number", "POS-A1-000010", "shiftId", shift.toString(), "discountMinor", 5000,
                        "items", List.of(Map.of("variantId", s.shirt(), "qty", 1, "unitMinor", 30000), Map.of("variantId", s.cap(), "qty", 2, "unitMinor", 10000)),
                        "payments", List.of(Map.of("method", "CASH", "amountMinor", 45000)))),
                op(3, "RETURN", s.owner(), Map.of("orderId", order.toString(), "shiftId", shift.toString(), "refundMethod", "CASH", "items", List.of(Map.of("variantId", s.cap(), "qty", 1))))));
        assertThat(jdbc.sql("SELECT total_minor FROM commerce.orders WHERE id = :o").param("o", order).query(Long.class).single()).isEqualTo(45000);
        assertThat(jdbc.sql("SELECT sum(line_total_minor) FROM commerce.order_items WHERE order_id = :o").param("o", order).query(Long.class).single()).isEqualTo(45000);
        assertThat(jdbc.sql("SELECT refund_minor FROM commerce.order_returns WHERE order_id = :o").param("o", order).query(Long.class).single()).isEqualTo(9000);
    }
}

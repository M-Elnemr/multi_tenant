package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.affiliate.AffiliateService;
import com.platform.commerce.OrderEvents;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The shop's affiliate program: a marketer's link credits an order, the rate is frozen on the commission, and the commission follows the order to approved / reversed / paid. */
class AffiliateProgramIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired AffiliateService affiliate;
    @Autowired ApplicationEventPublisher events;

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch)).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, id, JsonPath.read(p, "$.variants[0].id"), method);
    }

    UUID order(Shop s, String phone, String ref, int qty) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY","ref":%s,
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), qty, s.zones(), ref == null ? "null" : "\"" + ref + "\"", phone);
        String res = ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(res, "$.orderId"));
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    void setStatus(Shop s, UUID order, String status) {
        jdbc.sql("UPDATE commerce.orders SET status = :st WHERE id = :o").param("st", status).param("o", order).update();
        events.publishEvent(new OrderEvents.OrderStatusChanged(s.id(), order, null, "X", status));
    }

    String program(Shop s, String json) throws Exception { return ok(onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/affiliate/program", json).andExpect(status().isOk())); }

    String partner(Shop s, String json) throws Exception { return ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners", json).andExpect(status().isCreated())); }

    @Test
    void aMarketersLinkCreditsTheOrderAtTheRateThatAppliedAndTheCommissionFollowsTheOrder() throws Exception {
        Shop s = shop();
        program(s, "{\"enabled\":true,\"percent\":10,\"holdDays\":0}");
        String p = partner(s, "{\"name\":\"Sara Marketer\",\"phone\":\"01099999999\"}");
        String code = JsonPath.read(p, "$.code");
        assertThat(code).hasSize(8);
        // a visit through the link is counted and tells the page how long to remember it
        onHost(s.t().host(), null, "POST", "/api/v1/shop/affiliate/visit", "{\"ref\":\"" + code.toLowerCase() + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.days").value(30));
        onHost(s.t().host(), null, "POST", "/api/v1/shop/affiliate/visit", "{\"ref\":\"NOPE1234\"}").andExpect(jsonPath("$.valid").value(false));

        UUID o = order(s, phone(), code, 2);   // 2 x 200.00 = 400.00, shipping excluded, 10% = 40.00
        var c = jdbc.sql("SELECT base_minor, percent, amount_minor, status FROM affiliate.commissions WHERE order_id = :o").param("o", o).query().singleRow();
        assertThat(((Number) c.get("base_minor")).longValue()).isEqualTo(40000);
        assertThat(((Number) c.get("amount_minor")).longValue()).isEqualTo(4000);
        assertThat(c.get("status")).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT affiliate_code FROM commerce.orders WHERE id = :o").param("o", o).query(String.class).single()).isEqualTo(code);

        // the rate is frozen on the commission: a later change of the program does not touch it
        program(s, "{\"percent\":25}");
        assertThat(jdbc.sql("SELECT amount_minor FROM affiliate.commissions WHERE order_id = :o").param("o", o).query(Long.class).single()).isEqualTo(4000);

        // arrived with no hold period: the hourly job approves it, then the shop reports the payment
        setStatus(s, o, "ARRIVED");
        affiliate.approveDue();
        assertThat(jdbc.sql("SELECT status FROM affiliate.commissions WHERE order_id = :o").param("o", o).query(String.class).single()).isEqualTo("APPROVED");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/affiliate", null).andExpect(status().isOk()).andExpect(jsonPath("$.summary.owedMinor").value(4000)).andExpect(jsonPath("$.partners[0].visits").value(1));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + JsonPath.read(p, "$.id") + "/payout", null).andExpect(status().isOk()).andExpect(jsonPath("$.paidMinor").value(4000));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + JsonPath.read(p, "$.id") + "/payout", null).andExpect(status().isConflict());   // nothing left to pay
        assertThat(jdbc.sql("SELECT string_agg(kind, ',' ORDER BY created_at) FROM affiliate.ledger WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("ACCRUE,APPROVE,PAYOUT");
    }

    @Test
    void aCancelledOrderReversesItsCommissionAndNobodyEarnsOnTheirOwnOrOnADisabledProgram() throws Exception {
        Shop s = shop();
        String p = partner(s, "{\"name\":\"Ali\",\"phone\":\"01011112222\",\"percent\":20}");   // a personal rate; the program is still off
        String code = JsonPath.read(p, "$.code");
        UUID off = order(s, phone(), code, 1);
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", off).query(Long.class).single()).isZero();   // the program is disabled

        program(s, "{\"enabled\":true,\"percent\":5}");
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", order(s, "01011112222", code, 1)).query(Long.class).single()).isZero();   // the marketer's own phone

        UUID o = order(s, phone(), code, 1);   // 200.00 at the marketer's own 20% = 40.00
        assertThat(jdbc.sql("SELECT amount_minor FROM affiliate.commissions WHERE order_id = :o").param("o", o).query(Long.class).single()).isEqualTo(4000);
        setStatus(s, o, "CANCELLED");
        assertThat(jdbc.sql("SELECT status FROM affiliate.commissions WHERE order_id = :o").param("o", o).query(String.class).single()).isEqualTo("REVERSED");
        assertThat(jdbc.sql("SELECT sum(amount_minor) FROM affiliate.ledger WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isZero();   // accrual and reversal cancel out

        // a paused marketer earns nothing new; an unknown code is ignored and the order still goes through
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/affiliate/partners/" + JsonPath.read(p, "$.id"), "{\"status\":\"PAUSED\"}").andExpect(status().isOk());
        UUID paused = order(s, phone(), code, 1);
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", paused).query(Long.class).single()).isZero();
        order(s, phone(), "ZZZZZZZZ", 1);
        // validation
        onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/affiliate/program", "{\"percent\":80}").andExpect(status().isBadRequest());
    }

    @Test
    void aProgramIsPrivateToItsShop() throws Exception {
        Shop a = shop(), b = shop();
        partner(a, "{\"name\":\"Only A\"}");
        onHost(b.t().host(), b.t().access(), "GET", "/api/v1/store/affiliate", null).andExpect(status().isOk()).andExpect(jsonPath("$.partners.length()").value(0));
    }
}

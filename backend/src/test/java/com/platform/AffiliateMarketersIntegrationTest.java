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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Marketer accounts, the marketplace, applications, offers, payout requests, reviews, chat, disputes, fraud flags and the platform admin's oversight. */
class AffiliateMarketersIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired AffiliateService affiliate;
    @Autowired ApplicationEventPublisher events;

    record Shop(Tenant t, UUID id, String variant, String zones) {}
    record Mk(String token, String id, String phone) {}

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

    UUID order(Shop s, String phone, String ref, int qty, String addr) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY","ref":%s,
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"%s","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), qty, s.zones(), ref == null ? "null" : "\"" + ref + "\"", phone, addr);
        return UUID.fromString(JsonPath.read(ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated())), "$.orderId"));
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    /** A call on the platform site as a marketer (their own token header). */
    ResultActions mk(Mk m, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder b = switch (method) { case "POST" -> MockMvcRequestBuilders.post(path); case "PUT" -> MockMvcRequestBuilders.put(path); default -> MockMvcRequestBuilders.get(path); };
        b.header("Host", "platform.test");
        if (m != null) b.header("X-Marketer-Token", m.token());
        if (body != null) b.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

    Mk register(String name) throws Exception {
        String ph = "010" + (20_000_000L + System.nanoTime() % 70_000_000L);
        String res = ok(mk(null, "POST", "/api/v1/marketer/register", "{\"name\":\"%s\",\"phone\":\"%s\",\"password\":\"marketPass1\"}".formatted(name, ph)).andExpect(status().isCreated()));
        return new Mk(JsonPath.read(res, "$.token"), JsonPath.read(res, "$.account.id"), ph);
    }

    void setStatus(Shop s, UUID order, String st) {
        jdbc.sql("UPDATE commerce.orders SET status = :st WHERE id = :o").param("st", st).param("o", order).update();
        events.publishEvent(new OrderEvents.OrderStatusChanged(s.id(), order, null, "X", st));
    }

    void program(Shop s, String json) throws Exception { onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/affiliate/program", json).andExpect(status().isOk()); }

    @Test
    void aMarketerRegistersAppliesGetsApprovedEarnsRequestsAPayoutAndTheShopSettlesIt() throws Exception {
        Shop s = shop();
        Mk m = register("Nour Marketer");
        // security: the marketer endpoints refuse everybody else, and a marketer token opens nothing else
        mk(null, "GET", "/api/v1/marketer/shops", null).andExpect(status().is4xxClientError());
        mk(new Mk("not-a-token", "x", "x"), "GET", "/api/v1/marketer/shops", null).andExpect(status().is4xxClientError());
        onHost(s.t().host(), null, "GET", "/api/v1/store/affiliate", null).andExpect(status().is4xxClientError());
        MockHttpServletRequestBuilder asStaff = MockMvcRequestBuilders.get("/api/v1/store/affiliate").header("Host", s.t().host()).header("X-Marketer-Token", m.token());
        mvc.perform(asStaff).andExpect(status().is4xxClientError());   // the marketer token is useless on a shop's own API

        // not listed yet: the shop is invisible in the marketplace and cannot be applied to
        program(s, "{\"enabled\":true,\"percent\":10,\"holdDays\":0,\"minPayoutMinor\":1000,\"pitch\":\"Fashion for everyone\"}");
        mk(m, "POST", "/api/v1/marketer/shops/" + s.id() + "/apply", "{}").andExpect(status().isNotFound());
        program(s, "{\"listed\":true}");
        mk(m, "GET", "/api/v1/marketer/marketplace?q=" + s.t().slug().substring(0, 8), null).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].pitch").value("Fashion for everyone")).andExpect(jsonPath("$.data[0].myStatus").doesNotExist());

        // apply: waits for the shop; no link until approved
        String applied = ok(mk(m, "POST", "/api/v1/marketer/shops/" + s.id() + "/apply", "{\"message\":\"I have 20k followers\"}").andExpect(status().isCreated()));
        String partnerId = JsonPath.read(applied, "$.partnerId");
        assertThat((String) JsonPath.read(applied, "$.status")).isEqualTo("PENDING");
        mk(m, "POST", "/api/v1/marketer/shops/" + s.id() + "/apply", "{}").andExpect(status().isConflict());
        mk(m, "GET", "/api/v1/marketer/shops/" + partnerId + "/link", null).andExpect(status().isConflict());
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/affiliate/badges", null).andExpect(jsonPath("$.applications").value(1));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/decision", "{\"decision\":\"APPROVE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/decision", "{\"decision\":\"APPROVE\"}").andExpect(status().isConflict());   // already decided
        String link = JsonPath.read(ok(mk(m, "GET", "/api/v1/marketer/shops/" + partnerId + "/link?campaign=insta-story", null).andExpect(status().isOk())), "$.ref");
        assertThat(link).endsWith(":insta-story");

        // a limited offer for this marketer lifts the rate; the order carries the campaign
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/offers", "{\"partnerId\":\"%s\",\"title\":\"Weekend boost\",\"percent\":15}".formatted(partnerId)).andExpect(status().isCreated());
        mk(m, "GET", "/api/v1/marketer/offers", null).andExpect(jsonPath("$[0].percent").value(15.0));
        UUID o = order(s, phone(), link, 2, "12 Tahrir Street, Dokki");   // 2 x 200.00 = 400.00 at 15% = 60.00
        var c = jdbc.sql("SELECT amount_minor, percent, campaign, offer_id FROM affiliate.commissions WHERE order_id = :o").param("o", o).query().singleRow();
        assertThat(((Number) c.get("amount_minor")).longValue()).isEqualTo(6000);
        assertThat(c.get("campaign")).isEqualTo("insta-story");
        assertThat(c.get("offer_id")).isNotNull();
        mk(m, "GET", "/api/v1/marketer/notifications", null).andExpect(jsonPath("$.unread").value(3));   // approved + the offer + the new order

        // nothing to request while pending; after arrival (no hold) and approval the marketer asks the shop to pay
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/payouts", "{}").andExpect(status().isConflict());
        setStatus(s, o, "ARRIVED");
        affiliate.approveDue();
        String req = ok(mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/payouts", "{\"note\":\"InstaPay please\"}").andExpect(status().isCreated()));
        assertThat(((Number) JsonPath.read(req, "$.amountMinor")).longValue()).isEqualTo(6000);
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/payouts", "{}").andExpect(status().isConflict());   // one open request at a time
        // the shop's direct "record payment" cannot also pay what is already requested
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/payout", "{}").andExpect(status().isConflict());
        String reqId = JsonPath.read(req, "$.id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/payouts/" + reqId + "/paid", "{\"evidence\":\"\"}").andExpect(status().isBadRequest());   // proof is required
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/payouts/" + reqId + "/paid", "{\"evidence\":\"InstaPay ref 998877\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM affiliate.commissions WHERE order_id = :o").param("o", o).query(String.class).single()).isEqualTo("PAID");
        mk(m, "GET", "/api/v1/marketer/payouts", null).andExpect(jsonPath("$[0].status").value("PAID")).andExpect(jsonPath("$[0].evidence").value("InstaPay ref 998877"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/payouts/" + reqId + "/paid", "{\"evidence\":\"again\"}").andExpect(status().isConflict());   // cannot be paid twice

        // ratings need a real relationship (there is one now) and are one per direction
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/rating", "{\"rating\":5,\"comment\":\"pays on time\"}").andExpect(status().isOk());
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/rating", "{\"rating\":9}").andExpect(status().isBadRequest());
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/rating", "{\"rating\":4}").andExpect(status().isOk());
        // chat both ways, with the unread counts
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/messages", "{\"body\":\"Can I get product photos?\"}").andExpect(status().isOk());
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/affiliate/badges", null).andExpect(jsonPath("$.messages").value(1));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/messages", "{\"body\":\"Sure, sending now\"}").andExpect(status().isOk());
        mk(m, "GET", "/api/v1/marketer/shops/" + partnerId + "/messages", null).andExpect(jsonPath("$.length()").value(2));
        // a dispute from the marketer, resolved by the shop
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/disputes", "{\"reason\":\"One order is missing\"}").andExpect(status().isCreated());
        String did = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/affiliate/disputes", null)), "$[0].id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/disputes/" + did + "/resolve", "{\"resolution\":\"Found and credited\"}").andExpect(status().isOk());
        mk(m, "GET", "/api/v1/marketer/disputes", null).andExpect(jsonPath("$[0].status").value("RESOLVED"));
        // the marketer's numbers
        mk(m, "GET", "/api/v1/marketer/stats", null).andExpect(jsonPath("$.paidMinor").value(6000)).andExpect(jsonPath("$.byCampaign[0].campaign").value("insta-story"));
        assertThat(jdbc.sql("SELECT string_agg(kind, ',' ORDER BY created_at, kind) FROM affiliate.ledger WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).contains("ACCRUE").contains("APPROVE").contains("LOCK").contains("PAYOUT");
    }

    @Test
    void aReversalShrinksAnOpenPayoutRequestAndABlockedOrSuspendedMarketerEarnsNothing() throws Exception {
        Shop s = shop();
        program(s, "{\"enabled\":true,\"percent\":10,\"holdDays\":0,\"listed\":true,\"autoApprove\":true}");
        Mk m = register("Auto Marketer");
        String partnerId = JsonPath.read(ok(mk(m, "POST", "/api/v1/marketer/shops/" + s.id() + "/apply", "{}").andExpect(status().isCreated())), "$.partnerId");   // auto-approved
        String ref = JsonPath.read(ok(mk(m, "GET", "/api/v1/marketer/shops/" + partnerId + "/link", null).andExpect(status().isOk())), "$.ref");
        UUID a = order(s, phone(), ref, 1, "5 Nile Street, Giza"), b = order(s, phone(), ref, 1, "6 Nile Street, Giza");   // 20.00 each
        setStatus(s, a, "ARRIVED"); setStatus(s, b, "ARRIVED");
        affiliate.approveDue();
        mk(m, "POST", "/api/v1/marketer/shops/" + partnerId + "/payouts", "{}").andExpect(status().isCreated());
        setStatus(s, b, "RETURNED");   // one order comes back while the request is open
        assertThat(jdbc.sql("SELECT amount_minor FROM affiliate.payout_requests WHERE partner_id = :p").param("p", UUID.fromString(partnerId)).query(Long.class).single()).isEqualTo(2000);
        assertThat(jdbc.sql("SELECT status FROM affiliate.commissions WHERE order_id = :o").param("o", b).query(String.class).single()).isEqualTo("REVERSED");
        // a marketer can cancel their own request, which frees the commissions again
        String rid = JsonPath.read(ok(mk(m, "GET", "/api/v1/marketer/payouts", null)), "$[0].id");
        mk(m, "POST", "/api/v1/marketer/payouts/" + rid + "/cancel", null).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT payout_request_id FROM affiliate.commissions WHERE order_id = :o").param("o", a).query(UUID.class).optional()).isEmpty();
        // blocked by the shop: no new credit; unblocked: credit again
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/decision", "{\"decision\":\"BLOCK\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", order(s, phone(), ref, 1, "7 Nile Street, Giza")).query(Long.class).single()).isZero();
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/affiliate/partners/" + partnerId + "/decision", "{\"decision\":\"UNBLOCK\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", order(s, phone(), ref, 1, "8 Nile Street, Giza")).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void fraudSignalsAreFlaggedForThePlatformAdminWhoReviewsThemAndOverseesTheProgram() throws Exception {
        Shop s = shop();
        program(s, "{\"enabled\":true,\"percent\":10,\"holdDays\":0,\"listed\":true,\"autoApprove\":true}");
        Mk m = register("Suspicious Marketer");
        String partnerId = JsonPath.read(ok(mk(m, "POST", "/api/v1/marketer/shops/" + s.id() + "/apply", "{}").andExpect(status().isCreated())), "$.partnerId");
        String ref = JsonPath.read(ok(mk(m, "GET", "/api/v1/marketer/shops/" + partnerId + "/link", null)), "$.ref");
        // self-referral: the marketer's own phone earns nothing and is flagged
        UUID own = order(s, m.phone(), ref, 1, "9 Own Street, Cairo");
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE order_id = :o").param("o", own).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM affiliate.fraud_flags WHERE partner_id = :p AND kind = 'SELF_REFERRAL' AND status = 'OPEN'").param("p", UUID.fromString(partnerId)).query(Long.class).single()).isEqualTo(1);
        // the same customer three times, and three phones to one address
        String same = phone();
        for (int i = 0; i < 3; i++) order(s, same, ref, 1, "1 Shared Street, Cairo " + i);
        for (int i = 0; i < 3; i++) order(s, phone(), ref, 1, "77 Same Address Street, Dokki");
        var kinds = jdbc.sql("SELECT string_agg(DISTINCT kind, ',' ORDER BY kind) FROM affiliate.fraud_flags WHERE partner_id = :p").param("p", UUID.fromString(partnerId)).query(String.class).single();
        assertThat(kinds).contains("DUPLICATE_CUSTOMER").contains("SHARED_ADDRESS").contains("SELF_REFERRAL");

        // the platform admin: sees it, filters, reviews, suspends the marketer, and the audit trail records it
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String admin = JsonPath.read(ok(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk())), "$.accessToken");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/platform/affiliate/overview", null).andExpect(status().isForbidden());   // a shop owner is not a platform admin
        onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/overview", null).andExpect(status().isOk()).andExpect(jsonPath("$.flagsOpen").isNumber());
        String flags = ok(onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/flags?status=OPEN&kind=SELF_REFERRAL&q=" + s.t().slug(), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)));
        String flagId = JsonPath.read(flags, "$.data[0].id");
        onHost("platform.test", admin, "POST", "/api/v1/platform/affiliate/flags/" + flagId + "/review", "{\"decision\":\"CONFIRMED\",\"note\":\"own number\",\"suspendMarketer\":true}").andExpect(status().isOk());
        mk(m, "GET", "/api/v1/marketer/shops", null).andExpect(status().is4xxClientError());   // suspended: the token no longer works
        mk(null, "POST", "/api/v1/marketer/login", "{\"phone\":\"%s\",\"password\":\"marketPass1\"}".formatted(m.phone())).andExpect(status().isForbidden());
        onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/marketers?status=SUSPENDED&q=Suspicious", null).andExpect(jsonPath("$.total").value(1));
        onHost("platform.test", admin, "POST", "/api/v1/platform/affiliate/marketers/" + m.id() + "/status", "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/audit?action=FLAG_CONFIRMED&q=" + s.t().slug(), null).andExpect(jsonPath("$.total").value(1));
        onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/shops?q=" + s.t().slug(), null).andExpect(jsonPath("$.data[0].enabled").value(true));
        onHost("platform.test", admin, "GET", "/api/v1/platform/affiliate/payouts?status=REQUESTED", null).andExpect(status().isOk());
        // global switches: closed registration stops new marketers; the cap limits any rate
        onHost("platform.test", admin, "PUT", "/api/v1/platform/affiliate/settings", "{\"registrationOpen\":false,\"maxPercent\":12}").andExpect(status().isOk());
        mk(null, "POST", "/api/v1/marketer/register", "{\"name\":\"Late\",\"phone\":\"01055555555\",\"password\":\"marketPass1\"}").andExpect(status().isForbidden());
        onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/affiliate/program", "{\"percent\":20}").andExpect(status().isBadRequest());
        onHost("platform.test", admin, "PUT", "/api/v1/platform/affiliate/settings", "{\"registrationOpen\":true,\"maxPercent\":50}").andExpect(status().isOk());
    }
}

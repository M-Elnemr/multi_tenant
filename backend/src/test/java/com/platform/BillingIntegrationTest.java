package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.BillingService;
import com.platform.billing.MockPaymentProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

class BillingIntegrationTest extends IntegrationTestBase {
    @Autowired MockPaymentProvider provider;
    @Autowired BillingService billing;
    @Autowired JdbcClient jdbc;

    String payInvoice(Tenant t, String plan) throws Exception {
        String res = onHost(t.host(), t.access(), "POST", "/api/v1/billing/subscription/change", "{\"planCode\":\"%s\"}".formatted(plan))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.invoiceId");
    }

    @Test
    void newTenantStartsFreeTrialAndPlansAreListedForItsType() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/subscription", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TRIALING")).andExpect(jsonPath("$.planCode").value("STORE_FREE"));
        onHost(t.host(), null, "GET", "/api/v1/billing/plans", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].tenantType").value("STORE"))
                .andExpect(jsonPath("$[0].priceMinor").value(0)).andExpect(jsonPath("$[1].priceMinor").value(50000)).andExpect(jsonPath("$[2].priceMinor").value(75000));
        // custom domains are part of the trial (Pro features)
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.trial-" + uniq() + ".com\"}").andExpect(status().isCreated());
    }

    @Test
    void payingTheSinglePlanViaWebhookIsIdempotent() throws Exception {
        Tenant t = onboard("STORE");
        String invoice = payInvoice(t, "STORE_PRO");
        String evt = "evt_" + uniq();
        String body = "{\"eventId\":\"%s\",\"type\":\"payment.succeeded\",\"invoiceId\":\"%s\"}".formatted(evt, invoice);

        // forged webhook rejected
        mvc.perform(post("/api/v1/billing/webhooks/mock").header("Host", "platform.test").header("X-Signature", "deadbeef")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT status FROM billing.subscription_invoices WHERE id = :i").param("i", UUID.fromString(invoice)).query(String.class).single()).isEqualTo("OPEN");

        // real webhook applies the plan; the same delivery again is a no-op
        for (String expected : new String[] {"PROCESSED", "DUPLICATE"}) {
            mvc.perform(post("/api/v1/billing/webhooks/mock").header("Host", "platform.test").header("X-Signature", provider.sign(body))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(expected));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM billing.webhook_events WHERE event_id = :e").param("e", evt).query(Long.class).single()).isEqualTo(1);
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/subscription", null)
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.planCode").value("STORE_PRO"));
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/invoices", null).andExpect(jsonPath("$[0].status").value("PAID"));
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = (SELECT tenant_id FROM billing.subscription_invoices WHERE id = :i)")
                .param("i", UUID.fromString(invoice)).query(String.class).single()).isEqualTo("ACTIVE");

        // the single plan includes custom domains
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.paid-" + uniq() + ".com\"}").andExpect(status().isCreated());
    }

    @Test
    void staffLimitFromPlanIsEnforced() throws Exception {
        Tenant t = onboard("CLINIC");
        String invoice = payInvoice(t, "CLINIC_PRO");
        String body = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"invoiceId\":\"%s\"}".formatted(uniq(), invoice);
        mvc.perform(post("/api/v1/billing/webhooks/mock").header("Host", "platform.test").header("X-Signature", provider.sign(body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        // the plan allows 25 staff (the owner counts as 1)
        for (int i = 0; i < 24; i++) {
            onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                    "{\"firstName\":\"S%d\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(i, nextPhone())).andExpect(status().isCreated());
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"S25\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(nextPhone()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
    }

    @Test
    void freeTrialEndDisablesTheBusinessAtOnceButNeverDeletesData() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '1 hour' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("SUSPENDED");
        assertThat(jdbc.sql("SELECT status FROM billing.tenant_subscriptions WHERE tenant_id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("EXPIRED");
        // disabled: the site is closed to everyone, the owner can still sign in and see / ask for a plan
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"x\",\"phone\":\"" + nextPhone() + "\",\"role\":\"STORE_MANAGER\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/status", null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DISABLED"));
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t").param("t", tenantId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void paidPeriodEndWaitsForRenewForFourteenDaysThenDisables() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        billing.activatePlan(tenantId, billing.planId("STORE_STANDARD"), 30);
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/status", null).andExpect(jsonPath("$.state").value("OK")).andExpect(jsonPath("$.planCode").value("STORE_STANDARD"));

        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '1 hour' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("PAST_DUE");
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/status", null).andExpect(jsonPath("$.state").value("WAIT_RENEW")).andExpect(jsonPath("$.daysLeft").value(14));
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk());   // still open while waiting for renew

        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '13 days' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("PAST_DUE");

        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '15 days' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("SUSPENDED");
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
    }
}

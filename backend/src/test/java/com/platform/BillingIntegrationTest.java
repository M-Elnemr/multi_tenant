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
    void newTenantStartsProTrialAndPlansAreListedForItsType() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/subscription", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TRIALING")).andExpect(jsonPath("$.planCode").value("STORE_PRO"));
        onHost(t.host(), null, "GET", "/api/v1/billing/plans", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].tenantType").value("STORE"));
        // custom domains are part of the trial (Pro features)
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.trial-" + uniq() + ".com\"}").andExpect(status().isCreated());
    }

    @Test
    void paidChangeToStarterLosesCustomDomainAndWebhookIsIdempotent() throws Exception {
        Tenant t = onboard("STORE");
        String invoice = payInvoice(t, "STORE_STARTER");
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
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.planCode").value("STORE_STARTER"));
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/invoices", null).andExpect(jsonPath("$[0].status").value("PAID"));
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = (SELECT tenant_id FROM billing.subscription_invoices WHERE id = :i)")
                .param("i", UUID.fromString(invoice)).query(String.class).single()).isEqualTo("ACTIVE");

        // Starter has no custom domain: gated by the entitlement service, not by plan name
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.nope-" + uniq() + ".com\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_AVAILABLE"));
    }

    @Test
    void staffLimitFromPlanIsEnforced() throws Exception {
        Tenant t = onboard("CLINIC");
        String invoice = payInvoice(t, "CLINIC_STARTER");
        String body = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"invoiceId\":\"%s\"}".formatted(uniq(), invoice);
        mvc.perform(post("/api/v1/billing/webhooks/mock").header("Host", "platform.test").header("X-Signature", provider.sign(body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        // Clinic Starter: max_staff = 3 (owner counts as 1)
        for (int i = 0; i < 2; i++) {
            onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                    "{\"firstName\":\"S%d\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(i, nextPhone())).andExpect(status().isCreated());
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"S3\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(nextPhone()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
    }

    @Test
    void trialEndMovesToPastDueThenSuspendedButNeverDeletesData() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '1 hour' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("PAST_DUE");
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/subscription", null).andExpect(jsonPath("$.status").value("PAST_DUE"));

        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '10 days' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()).isEqualTo("SUSPENDED");
        // suspended: writes blocked, data still there
        onHost(t.host(), t.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"x\",\"phone\":\"" + nextPhone() + "\",\"role\":\"STORE_MANAGER\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t").param("t", tenantId).query(Long.class).single()).isEqualTo(1);
    }
}

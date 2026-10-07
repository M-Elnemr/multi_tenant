package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.platform.RetentionJob;
import com.platform.shared.RateLimitStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ScaleSupportIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired RetentionJob retention;
    @Autowired RateLimitStore store;

    @Test
    void unknownHostsAreCachedAndANewTenantResolvesImmediatelyDespiteACachedUnknown() throws Exception {
        String slug = "later" + uniq();
        String host = slug + ".platform.test";
        onHost(host, null, "GET", "/api/v1/tenant/context", null).andExpect(status().isNotFound());   // cached as unknown
        String phone = nextPhone();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/onboarding/tenants").header("Host", "platform.test")
                .contentType("application/json").content("{\"type\":\"STORE\",\"name\":\"Late\",\"slug\":\"%s\",\"ownerFirstName\":\"L\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, phone))).andExpect(status().isCreated());
        onHost(host, null, "GET", "/api/v1/tenant/context", null).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug));   // not stuck on the cached 404
    }

    @Test
    void everyResponseCarriesARequestIdAndMetricsAreExposed() throws Exception {
        String id = "req-" + UUID.randomUUID();
        mvc.perform(get("/api/v1/billing/plans").header("Host", "platform.test").header("X-Request-Id", id)).andExpect(header().string("X-Request-Id", id));
        mvc.perform(get("/api/v1/billing/plans").header("Host", "platform.test").header("X-Request-Id", "bad id with spaces & <script>")).andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        String metrics = mvc.perform(get("/actuator/prometheus").header("Host", "platform.test")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("jvm_memory_used_bytes").contains("hikaricp_connections");
    }

    @Test
    void retentionRemovesOnlyOldChurnRowsAndNeverBusinessData() throws Exception {
        Tenant store = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", store.slug()).query(UUID.class).single();
        UUID user = jdbc.sql("SELECT user_id FROM core.user_tenant_memberships WHERE tenant_id = :t LIMIT 1").param("t", tenant).query(UUID.class).single();
        String tag = uniq();
        jdbc.sql("INSERT INTO notifications.outbox (channel, to_address, subject, body, status, created_at) VALUES ('EMAIL', :o,'s','b','SENT', now() - interval '40 days'), ('EMAIL', :n,'s','b','SENT', now()), ('EMAIL', :p,'s','b','PENDING', now() - interval '40 days')")
                .param("o", "old" + tag + "@x.com").param("n", "new" + tag + "@x.com").param("p", "pend" + tag + "@x.com").update();
        jdbc.sql("INSERT INTO notifications.notifications (user_id, tenant_id, notification_type, title, body, read_at, created_at) VALUES (:u,:t,'X','old read','b', now(), now() - interval '100 days'), (:u,:t,'X','old unread','b', NULL, now() - interval '100 days'), (:u,:t,'X','fresh','b', NULL, now())").param("u", user).param("t", tenant).update();
        jdbc.sql("INSERT INTO core.idempotency_keys (key, scope, created_at) VALUES (:k1,'onboarding', now() - interval '10 days'), (:k2,'onboarding', now())").param("k1", "old-" + tag).param("k2", "new-" + tag).update();
        long ordersBefore = jdbc.sql("SELECT count(*) FROM core.tenants").query(Long.class).single();

        retention.run();

        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE to_address = :a").param("a", "old" + tag + "@x.com").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE to_address IN (:a, :b)").param("a", "new" + tag + "@x.com").param("b", "pend" + tag + "@x.com").query(Long.class).single()).isEqualTo(2);   // recent and still-pending messages stay
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND title = 'old read'").param("t", tenant).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND title IN ('old unread','fresh')").param("t", tenant).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM core.idempotency_keys WHERE key = :k").param("k", "old-" + tag).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants").query(Long.class).single()).isEqualTo(ordersBefore);   // business data untouched
    }

    @Test
    void rateLimitStoreCountsPerKeyWithinAWindowAndCanBeReset() throws Exception {
        String k = "t-" + uniq();
        for (int i = 0; i < 3; i++) assertThat(store.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();
        assertThat(store.tryAcquire(k, 3, Duration.ofSeconds(30))).isFalse();
        assertThat(store.tryAcquire(k + "-other", 3, Duration.ofSeconds(30))).isTrue();
        store.reset(k);
        assertThat(store.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();
        assumeThat(true).isTrue();
    }
}

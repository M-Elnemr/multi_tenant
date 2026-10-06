package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.shared.RateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PlatformAdminIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    /** A platform operator: a normal account that holds a platform role (created from env at bootstrap in real life). */
    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void platformOperatorSeesTenantsAndCanSuspendButTenantOwnersCannot() throws Exception {
        String admin = platformToken();
        Tenant store = onboard("STORE");
        Tenant clinic = onboard("CLINIC");

        onHost("platform.test", admin, "GET", "/api/v1/platform/overview", null).andExpect(status().isOk()).andExpect(jsonPath("$.totalTenants").isNumber());
        String list = onHost("platform.test", admin, "GET", "/api/v1/platform/tenants?type=CLINIC&q=" + clinic.slug(), null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].planCode").value("CLINIC_PRO")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$.data[0].id");
        String detail = onHost("platform.test", admin, "GET", "/api/v1/platform/tenants/" + id, null).andExpect(status().isOk()).andExpect(jsonPath("$.domains[0].host").value(clinic.host())).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("patient");   // control plane exposes metadata only, never clinical content

        // tenant owners have no platform power
        onHost("platform.test", store.access(), "GET", "/api/v1/platform/tenants", null).andExpect(status().isForbidden());
        onHost("platform.test", null, "GET", "/api/v1/platform/overview", null).andExpect(status().isUnauthorized());

        // suspension blocks the tenant's writes (reads stay) and is audited
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/status", "{\"status\":\"SUSPENDED\",\"reason\":\"abuse report\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        onHost(clinic.host(), clinic.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"X\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
        onHost(clinic.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk());
        onHost("platform.test", admin, "GET", "/api/v1/platform/audit?action=TENANT_STATUS_CHANGED&tenantId=" + id, null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1));
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/status", "{\"status\":\"ACTIVE\"}").andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/status", "{\"status\":\"BOGUS\"}").andExpect(status().isBadRequest());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        onHost("platform.test", null, "GET", "/api/v1/billing/plans", null).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=()")));
    }

    @Test
    void rateLimiterAllowsUpToTheLimitThenBlocksPerKey() {
        RateLimiter l = new RateLimiter();
        for (int i = 0; i < 3; i++) assertThat(l.tryAcquire("a", 3, Duration.ofMinutes(1))).isTrue();
        assertThat(l.tryAcquire("a", 3, Duration.ofMinutes(1))).isFalse();
        assertThat(l.tryAcquire("b", 3, Duration.ofMinutes(1))).isTrue();   // other keys unaffected
        assertThat(l.tryAcquire("c", 1, Duration.ofMillis(20))).isTrue();
        try { Thread.sleep(40); } catch (InterruptedException ignored) { }
        assertThat(l.tryAcquire("c", 1, Duration.ofMillis(20))).isTrue();   // window rolled over
    }
}

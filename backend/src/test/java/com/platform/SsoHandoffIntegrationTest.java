package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** One sign-in for every place a person belongs to: a single-use, 60-second, tenant-bound ticket carries the session from the platform host to a tenant host. */
class SsoHandoffIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tenantId(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String ticket(Tenant owner, Tenant target) throws Exception {
        return JsonPath.read(body(onHost("platform.test", owner.access(), "POST", "/api/v1/auth/handoff", "{\"tenantId\":\"%s\"}".formatted(tenantId(target))).andExpect(status().isOk())), "$.ticket");
    }

    @Test
    void ticketOpensOnePlaceOnceAndOnlyThere() throws Exception {
        Tenant clinic = onboard("CLINIC");
        Tenant other = onboard("CLINIC");
        String t = ticket(clinic, clinic);

        // the clinic's own host swaps it for a session that works there without any password
        String res = body(onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"%s\"}".formatted(t)).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists()));
        String access = JsonPath.read(res, "$.accessToken");
        onHost(clinic.host(), access, "GET", "/api/v1/clinic/dashboard", null).andExpect(status().isOk());

        // single use
        onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"%s\"}".formatted(t)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TICKET"));

        // a ticket for one place is useless on another place's host
        String t2 = ticket(clinic, clinic);
        onHost(other.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"%s\"}".formatted(t2)).andExpect(status().isUnauthorized());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"%s\"}".formatted(t2)).andExpect(status().isOk());   // ...and was not burnt by the failed attempt elsewhere

        // expired tickets are refused
        String t3 = ticket(clinic, clinic);
        jdbc.sql("UPDATE core.sso_tickets SET expires_at = now() - interval '1 second' WHERE used_at IS NULL AND tenant_id = :t").param("t", tenantId(clinic)).update();
        onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"%s\"}".formatted(t3)).andExpect(status().isUnauthorized());

        // forged / garbage tickets
        onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"not-a-real-ticket-at-all-0123456789\"}").andExpect(status().isUnauthorized());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/handoff/redeem", "{\"ticket\":\"x\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void onlyMembersCanAskAndOnlyWhenSignedIn() throws Exception {
        Tenant clinic = onboard("CLINIC");
        Tenant stranger = onboard("STORE");
        // someone who does not belong to the place gets the same answer as for a place that does not exist
        onHost("platform.test", stranger.access(), "POST", "/api/v1/auth/handoff", "{\"tenantId\":\"%s\"}".formatted(tenantId(clinic))).andExpect(status().isNotFound());
        onHost("platform.test", stranger.access(), "POST", "/api/v1/auth/handoff", "{\"tenantId\":\"%s\"}".formatted(UUID.randomUUID())).andExpect(status().isNotFound());
        // not signed in at all
        onHost("platform.test", null, "POST", "/api/v1/auth/handoff", "{\"tenantId\":\"%s\"}".formatted(tenantId(clinic))).andExpect(status().isUnauthorized());
        // only the hash is stored
        String t = ticket(clinic, clinic);
        assertThat(jdbc.sql("SELECT count(*) FROM core.sso_tickets WHERE token_hash = :t").param("t", t).query(Long.class).single()).isZero();
    }
}

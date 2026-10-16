package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.billing.BillingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** example.com and www.example.com are one custom domain: add either, verify once, both open the business. */
class CustomDomainAliasIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired BillingService billing;
    @Autowired com.platform.core.tenant.TenantDirectory directory;

    void verify(String host) {
        jdbc.sql("UPDATE core.tenant_domains SET is_verified = TRUE, verified_at = now() WHERE host = :h").param("h", host).update();
        directory.invalidateAll();   // in real life the verification endpoint / job clears the lookup cache
    }

    @Test
    void addingTheRootDomainAlsoServesWwwAndTheOtherWayRound() throws Exception {
        Tenant shop = onboard("STORE");
        String root = "a" + uniq() + ".com";
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"" + root + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.alias").value("www." + root)).andExpect(jsonPath("$.dnsInstructions.length()").value(3));
        // not verified yet: neither spelling opens the shop
        onHost(root, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isNotFound());
        onHost("www." + root, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isNotFound());
        verify(root);
        for (var h : new String[] {root, "www." + root}) onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk());

        String www = "www.b" + uniq() + ".com";
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"" + www + "\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.alias").value(www.substring(4)));
        verify(www);
        for (var h : new String[] {www, www.substring(4)}) onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk());
    }

    @Test
    void anotherBusinessCannotTakeTheOtherSpellingAndAPlanWithoutDomainsStopsBoth() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("CLINIC");
        String root = "c" + uniq() + ".com";
        onHost(a.host(), a.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"" + root + "\"}").andExpect(status().isCreated());
        onHost(b.host(), b.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www." + root + "\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOMAIN_TAKEN"));
        verify(root);
        onHost("www." + root, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk());

        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", a.slug()).query(UUID.class).single();
        billing.activatePlan(id, billing.planId("STORE_STANDARD"), 30);   // Standard has no custom domain: both spellings stop
        for (var h : new String[] {root, "www." + root}) onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenant_domains WHERE host = :h").param("h", root).query(Long.class).single()).isEqualTo(1);
    }
}

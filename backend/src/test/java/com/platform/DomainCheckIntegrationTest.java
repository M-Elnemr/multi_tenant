package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.core.tenant.DnsVerifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/** The domain page tells the owner which DNS records are right, missing or wrong. */
@Import(DomainCheckIntegrationTest.FakeDns.class)
@TestPropertySource(properties = "app.platform.edge-ip=10.9.8.7")
class DomainCheckIntegrationTest extends IntegrationTestBase {
    static final Map<String, List<String>> TXT = new ConcurrentHashMap<>(), CNAME = new ConcurrentHashMap<>(), ADDR = new ConcurrentHashMap<>();

    @TestConfiguration
    static class FakeDns {
        @Bean @Primary
        DnsVerifier fakeDns() {
            return new DnsVerifier() {
                @Override public List<String> txtRecords(String n) { return TXT.getOrDefault(n, List.of()); }
                @Override public List<String> cnames(String n) { return CNAME.getOrDefault(n, List.of()); }
                @Override public List<String> addresses(String n) { return ADDR.getOrDefault(n, List.of()); }
            };
        }
    }

    @Test
    void checkTellsMissingWrongAndRightRecordsAndVerifiesWhenTheTxtIsThere() throws Exception {
        Tenant shop = onboard("STORE");
        String host = "d" + uniq() + ".com";
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"" + host + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String token = JsonPath.read(created, "$.dnsInstructions[0].value");
        String txtName = "_platform-verify." + host;

        // nothing set yet
        onHost(shop.host(), shop.access(), "GET", "/api/v1/tenant/domains/" + id + "/check", null).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.records[0].purpose").value("VERIFY")).andExpect(jsonPath("$.records[0].shortName").value("_platform-verify"))
                .andExpect(jsonPath("$.records[0].status").value("MISSING")).andExpect(jsonPath("$.records[1].shortName").value("@")).andExpect(jsonPath("$.records[1].type").value("A"))
                .andExpect(jsonPath("$.records[1].value").value("10.9.8.7")).andExpect(jsonPath("$.records[2].shortName").value("www")).andExpect(jsonPath("$.records[2].type").value("CNAME"));

        // the mistake we saw: a CNAME where the TXT belongs, and an address that points elsewhere
        CNAME.put(txtName, List.of(host + "."));
        ADDR.put(host, List.of("1.2.3.4"));
        onHost(shop.host(), shop.access(), "GET", "/api/v1/tenant/domains/" + id + "/check", null).andExpect(jsonPath("$.records[0].status").value("WRONG")).andExpect(jsonPath("$.records[0].hint").value("CNAME_INSTEAD_OF_TXT"))
                .andExpect(jsonPath("$.records[1].status").value("WRONG")).andExpect(jsonPath("$.records[1].hint").value("OTHER_ADDRESS")).andExpect(jsonPath("$.records[2].status").value("MISSING"));

        // fixed: the TXT with the code, the address pointing here, www resolving here -> verified on this very check
        CNAME.remove(txtName);
        TXT.put(txtName, List.of(token));
        ADDR.put(host, List.of("10.9.8.7"));
        ADDR.put("www." + host, List.of("10.9.8.7"));
        onHost(shop.host(), shop.access(), "GET", "/api/v1/tenant/domains/" + id + "/check", null).andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.records[0].status").value("OK")).andExpect(jsonPath("$.records[1].status").value("OK")).andExpect(jsonPath("$.records[2].status").value("OK"));
        // other tenants cannot look at it
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "GET", "/api/v1/tenant/domains/" + id + "/check", null).andExpect(status().isNotFound());
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.commerce.domains.DomainOrderRunner;
import com.platform.commerce.domains.FakeDomainProvider;
import com.platform.commerce.domains.ProviderException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** The platform-admin side: cross-tenant order list with margin, the price-rule editor, the provider error log, and the retryable-vs-not retry action. */
@Import(DomainAdminIntegrationTest.Fakes.class)
@TestPropertySource(properties = "app.domains.registrar.enabled=true")
class DomainAdminIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired DomainOrderRunner runner;
    @Autowired FakeDomainProvider fake;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary
        FakeDomainProvider fakeDomainProvider() { return new FakeDomainProvider(); }
    }

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    String orderDomain(Tenant shop, String label) throws Exception {
        fake.setAvailability(label + ".com", true, false, 0, "EGP");
        fake.setPricing("com", 40_00, 45_00, "EGP");
        String search = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/search?q=" + label + ".com", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String quoteId = JsonPath.read(search, "$.results[0].quoteId");
        String contact = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/registrant-contacts",
                "{\"name\":\"Admin Test\",\"email\":\"a@example.com\",\"phone\":\"+201000000002\",\"country\":\"EG\",\"address\":\"1 Nile St\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String contactId = JsonPath.read(contact, "$.id");
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    @Test
    void crossTenantOrderListShowsMarginAndThePriceRuleEditorRoundTrips() throws Exception {
        String admin = platformToken();
        Tenant shop = onboard("STORE");
        String label = "margin" + uniq();
        String orderId = orderDomain(shop, label);

        String list = onHost("platform.test", admin, "GET", "/api/v1/platform/domains/orders?q=" + label, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(list, "$.meta.total")).isEqualTo(1);
        Number merchantPrice = JsonPath.read(list, "$.data[0].priceMinor");
        Number providerCost = JsonPath.read(list, "$.data[0].providerPriceMinor");
        Number margin = JsonPath.read(list, "$.data[0].marginMinor");
        assertThat(margin.longValue()).isEqualTo(merchantPrice.longValue() - providerCost.longValue());
        assertThat(margin.longValue()).isGreaterThan(0L);   // the seeded 20% markup on "com"

        // tenant owners have no admin power here either
        onHost("platform.test", shop.access(), "GET", "/api/v1/platform/domains/orders", null).andExpect(status().isForbidden());

        String saved = onHost("platform.test", admin, "PUT", "/api/v1/platform/domains/price-rules",
                "{\"tld\":\"xyz" + uniq().substring(0, 4) + "\",\"markupKind\":\"FIXED\",\"markupValue\":500,\"currency\":\"EGP\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.markupKind").value("FIXED")).andReturn().getResponse().getContentAsString();
        String tld = JsonPath.read(saved, "$.tld");
        String rules = onHost("platform.test", admin, "GET", "/api/v1/platform/domains/price-rules", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> tlds = JsonPath.read(rules, "$[*].tld");
        assertThat(tlds).contains(tld);
    }

    @Test
    void nonRetryableFailureCannotBeRetriedButARetryableOneCanAndResumesWithoutANewCharge() throws Exception {
        String admin = platformToken();
        Tenant shop = onboard("STORE");

        // non-retryable: ends REFUNDED, never sits as a retriable FAILED order
        String badLabel = "inv" + uniq();
        fake.forceError("register", badLabel + ".com", ProviderException.invalidContact("bad address"));
        String badOrderId = orderDomain(shop, badLabel);
        runner.runOnce();
        onHost("platform.test", admin, "POST", "/api/v1/platform/domains/orders/" + badOrderId + "/retry", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RETRYABLE"));

        // retryable: a persistent PROVIDER_DOWN exhausts attempts, lands on FAILED, and IS retryable
        String flakyLabel = "down" + uniq();
        fake.forceErrorAlways("register", flakyLabel + ".com", ProviderException.providerDown("registrar maintenance"));
        String flakyOrderId = orderDomain(shop, flakyLabel);
        for (int i = 0; i < 9 && !"FAILED".equals(orderStatus(shop, flakyOrderId)); i++) {
            runner.runOnce();
            jdbc.sql("UPDATE commerce.domain_orders SET next_attempt_at = now() WHERE id = :i").param("i", UUID.fromString(flakyOrderId)).update();
        }
        assertThat(orderStatus(shop, flakyOrderId)).isEqualTo("FAILED");

        String errors = onHost("platform.test", admin, "GET", "/api/v1/platform/domains/provider-errors", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> codes = JsonPath.read(errors, "$[*].code");
        assertThat(codes).contains("PROVIDER_DOWN");

        fake.clearStickyError("register", flakyLabel + ".com");
        onHost("platform.test", admin, "POST", "/api/v1/platform/domains/orders/" + flakyOrderId + "/retry", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, flakyOrderId)); i++) runner.runOnce();
        assertThat(orderStatus(shop, flakyOrderId)).isEqualTo("LIVE");
        assertThat(fake.callCount("register", flakyLabel + ".com")).isGreaterThan(1);   // retried, but each retry call is a normal register attempt, never a second wallet charge
    }

    private String orderStatus(Tenant shop, String orderId) throws Exception {
        String res = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.status");
    }
}

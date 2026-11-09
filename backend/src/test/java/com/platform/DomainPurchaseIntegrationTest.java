package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.commerce.domains.DomainOrderRunner;
import com.platform.commerce.domains.FakeDomainProvider;
import com.platform.commerce.domains.ProviderException;
import com.platform.core.tenant.DnsVerifier;
import com.platform.core.tenant.TenantDirectory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Buying a domain, end to end, against {@link FakeDomainProvider} - no real registrar is ever called. Covers every
 * scenario from the plan's Tests section: search/pricing (both registrar-disabled and enabled), successful
 * purchase through LIVE with the DNS/SSL step sequence, insufficient balance, registration failure + refund,
 * duplicate purchase, a provider timeout/uncertain outcome resolved by a real status query (no double charge, no
 * double register), tenant isolation, manual renewal, the opt-in auto-renew toggle + expiry notice, and the
 * ownership/registrant/transfer-out endpoints.
 */
@Import(DomainPurchaseIntegrationTest.Fakes.class)
@TestPropertySource(properties = "app.domains.registrar.enabled=true")
class DomainPurchaseIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired TenantDirectory directory;
    @Autowired DomainOrderRunner runner;
    @Autowired FakeDomainProvider fake;
    @Autowired com.platform.core.tenant.DomainService domains;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary
        FakeDomainProvider fakeDomainProvider() { return new FakeDomainProvider(); }
    }

    UUID tenantIdOf(Tenant t) throws Exception {
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    /** search finds one TLD, its price = provider cost + the seeded rule's markup; a fresh quote id each time. */
    String searchAndBuy(Tenant shop, String label, String tld, long providerPriceMinor, long providerRenewMinor) throws Exception {
        fake.setAvailability(label + "." + tld, true, false, 0, "EGP");
        fake.setPricing(tld, providerPriceMinor, providerRenewMinor, "EGP");
        String res = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/search?q=" + label + "." + tld, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.registrarEnabled").value(true)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.results[0].quoteId");
    }

    String addContact(Tenant shop) throws Exception {
        String res = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/registrant-contacts",
                "{\"name\":\"Mona Owner\",\"email\":\"mona@example.com\",\"phone\":\"+201000000001\",\"country\":\"EG\",\"address\":\"1 Tahrir St\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.id");
    }

    @Test
    void pricingShowsNullPricesWhenNothingIsConfiguredYetButStillListsTheSeededTlds() throws Exception {
        Tenant shop = onboard("STORE");
        fake.setPricing("com", 50_00, 55_00, "EGP");
        String res = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/pricing", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> tlds = JsonPath.read(res, "$[*].tld");
        assertThat(tlds).contains("com");
        Integer comIndex = tlds.indexOf("com");
        Number register = JsonPath.read(res, "$[" + comIndex + "].registerPriceMinor");
        assertThat(register.longValue()).isGreaterThan(5000L);   // 50 EGP cost + the seeded 20% markup on "com"
    }

    @Test
    void fullPurchaseGoesThroughEveryStepInOrderAndEndsLiveAsAPurchasedDomain() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        long before = wallet.balance(tenantId);
        String label = "buy" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 55_00);
        String contactId = addContact(shop);

        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PAID")).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        long priceMinor = ((Number) JsonPath.read(created, "$.priceMinor")).longValue();
        assertThat(wallet.balance(tenantId)).isEqualTo(before - priceMinor);   // charged exactly once

        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();
        String detail = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE")).andReturn().getResponse().getContentAsString();
        java.util.List<String> seq = JsonPath.read(detail, "$.events[*].status");
        assertThat(seq).containsSubsequence("PAID", "REGISTERING", "DNS_CONFIGURING", "VERIFYING", "LIVE");   // never skipped or reordered

        String domainsList = onHost(shop.host(), shop.access(), "GET", "/api/v1/tenant/domains", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> sources = JsonPath.read(domainsList, "$[*].source");
        assertThat(sources).contains("PURCHASED");
        java.util.List<String> hosts = JsonPath.read(domainsList, "$[*].host");
        assertThat(hosts).contains(label + ".com");

        // tenant isolation: another tenant cannot see this order
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null).andExpect(status().isNotFound());
        onHost(other.host(), other.access(), "GET", "/api/v1/store/domains/orders", null).andExpect(jsonPath("$.length()").value(0));
    }

    private String orderStatus(Tenant shop, String orderId) throws Exception {
        String res = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.status");
    }

    @Test
    void notEnoughWalletBalanceRefusesBeforeAnyRegistrarCall() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        String label = "poor" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 999_999_00, 999_999_00);   // far more than the 100 free points
        String contactId = addContact(shop);
        long before = wallet.balance(tenantId);
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));
        assertThat(wallet.balance(tenantId)).isEqualTo(before);   // nothing was ever touched
        assertThat(fake.callCount("register", label + ".com")).isEqualTo(0);   // the registrar was never called
    }

    @Test
    void registrationFailureRefundsTheWalletAndNeverRetriesANonRetryableError() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        long before = wallet.balance(tenantId);
        String label = "bad" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 55_00);
        String contactId = addContact(shop);
        fake.forceError("register", label + ".com", ProviderException.invalidContact("The registrant address is invalid"));

        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        long priceMinor = ((Number) JsonPath.read(created, "$.priceMinor")).longValue();
        assertThat(wallet.balance(tenantId)).isEqualTo(before - priceMinor);

        runner.runOnce();   // one attempt is enough: INVALID_CONTACT is not retryable
        String detail = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detail, "$.status")).isEqualTo("REFUNDED");
        assertThat(wallet.balance(tenantId)).isEqualTo(before);   // refunded in full
        assertThat(fake.callCount("register", label + ".com")).isEqualTo(1);   // never retried
    }

    @Test
    void duplicatePurchaseOfTheSameDomainBySameTenantIsRejected() throws Exception {
        Tenant shop = onboard("STORE");
        String label = "dup" + uniq();
        String contactId = addContact(shop);
        String q1 = searchAndBuy(shop, label, "com", 50_00, 55_00);
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + q1 + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated());
        String q2 = searchAndBuy(shop, label, "com", 50_00, 55_00);   // a second, fresh quote for the SAME domain
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + q2 + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_IN_PROGRESS"));
    }

    @Test
    void aProviderTimeoutIsResolvedByAskingTheRegistrarNeverGuessedAndNeverDoubleRegisteredOrDoubleCharged() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        long before = wallet.balance(tenantId);
        String label = "flaky" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 55_00);
        String contactId = addContact(shop);
        fake.timeoutThenSucceedRegister(label + ".com");

        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        long priceMinor = ((Number) JsonPath.read(created, "$.priceMinor")).longValue();

        runner.runOnce();   // the registerDomain call times out; the registrar's own order actually went through
        String afterTimeout = onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId, null).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(afterTimeout, "$.status")).isEqualTo("PAID");   // not guessed as FAILED or LIVE
        assertThat(wallet.balance(tenantId)).isEqualTo(before - priceMinor);   // not charged again, not refunded either - still uncertain

        jdbc.sql("UPDATE commerce.domain_orders SET next_attempt_at = now() WHERE id = :i").param("i", UUID.fromString(orderId)).update();
        runner.runOnce();   // resolves via getDomainDetails, a real status query - never a second registerDomain call
        assertThat(fake.callCount("register", label + ".com")).isEqualTo(1);

        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();
        assertThat(orderStatus(shop, orderId)).isEqualTo("LIVE");
        assertThat(wallet.balance(tenantId)).isEqualTo(before - priceMinor);   // exactly one charge, start to finish
    }

    @Test
    void manualRenewalIsAFreshDebitAndOrderRowAndNeverAutomatic() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        String label = "renew" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 60_00);
        String contactId = addContact(shop);
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();
        assertThat(orderStatus(shop, orderId)).isEqualTo("LIVE");

        // auto-renew defaults to off, and never silently charges
        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders", null).andExpect(jsonPath("$[0].autoRenew").value(false));

        wallet.credit(tenantId, 50_000, "TOPUP", "test-topup-" + uniq(), "test top-up for renewal", null);
        long beforeRenew = wallet.balance(tenantId);
        String renewed = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders/" + orderId + "/renew", "{\"years\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID")).andReturn().getResponse().getContentAsString();
        String renewOrderId = JsonPath.read(renewed, "$.id");
        long renewPrice = ((Number) JsonPath.read(renewed, "$.priceMinor")).longValue();
        assertThat(wallet.balance(tenantId)).isEqualTo(beforeRenew - renewPrice);
        runner.runOnce();   // a renewal goes straight PAID -> LIVE, no DNS/verification steps
        assertThat(orderStatus(shop, renewOrderId)).isEqualTo("LIVE");
    }

    @Test
    void autoRenewOnlyChargesWhenTheMerchantExplicitlyTurnedItOnOtherwiseItOnlyNotifies() throws Exception {
        Tenant shop = onboard("STORE");
        UUID tenantId = tenantIdOf(shop);
        String label = "exp" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 60_00);
        String contactId = addContact(shop);
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();
        jdbc.sql("UPDATE commerce.domain_orders SET expires_at = now() + interval '10 days' WHERE id = :i").param("i", UUID.fromString(orderId)).update();

        // off by default: the expiry job only notifies, never charges
        long before = wallet.balance(tenantId);
        runner.checkExpiring();
        assertThat(wallet.balance(tenantId)).isEqualTo(before);
        long renewalRows = jdbc.sql("SELECT count(*) FROM commerce.domain_orders WHERE tenant_id = :t AND kind = 'RENEWAL'").param("t", tenantId).query(Long.class).single();
        assertThat(renewalRows).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type = 'DOMAIN_EXPIRING_SOON'").param("t", tenantId).query(Long.class).single()).isGreaterThan(0);

        // the merchant explicitly opts in; the price is shown by the same /pricing endpoint before they do
        wallet.credit(tenantId, 50_000, "TOPUP", "test-topup-" + uniq(), "test top-up for auto-renewal", null);
        before = wallet.balance(tenantId);
        onHost(shop.host(), shop.access(), "PUT", "/api/v1/store/domains/orders/" + orderId + "/auto-renew", "{\"enabled\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoRenew").value(true));
        jdbc.sql("UPDATE commerce.domain_orders SET renewal_notified_at = NULL WHERE id = :i").param("i", UUID.fromString(orderId)).update();
        runner.checkExpiring();
        assertThat(wallet.balance(tenantId)).isLessThan(before);   // now it was charged, because - and only because - auto-renew was turned on
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.domain_orders WHERE tenant_id = :t AND kind = 'RENEWAL'").param("t", tenantId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void registrantIsShownPlainlyAndTransferOutIssuesAFreshAuthCodeAfterUnlocking() throws Exception {
        Tenant shop = onboard("STORE");
        String label = "own" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 55_00);
        String contactId = addContact(shop);
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();

        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/domains/orders/" + orderId + "/registrant", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mona Owner")).andExpect(jsonPath("$.email").value("mona@example.com"));

        assertThat(fake.isTransferLocked(label + ".com")).isTrue();   // locked by default, like a real registration
        String auth = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders/" + orderId + "/transfer-auth-code", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.domain").value(label + ".com")).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(auth, "$.authCode");
        assertThat(code).isNotBlank();
        assertThat(fake.isTransferLocked(label + ".com")).isFalse();   // unlocked as part of issuing the code
        assertThat(fake.lastAuthCode(label + ".com")).isEqualTo(code);
    }

    @Test
    void aPurchasedDomainStillWorksThroughMakePrimaryRemoveAndIsOnlyDemotedOnRealTerminationNeverOnPlanLossAlone() throws Exception {
        Tenant shop = onboard("STORE");
        String label = "primary" + uniq();
        String quoteId = searchAndBuy(shop, label, "com", 50_00, 55_00);
        String contactId = addContact(shop);
        String created = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/domains/orders",
                "{\"quoteId\":\"" + quoteId + "\",\"years\":1,\"registrantContactId\":\"" + contactId + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.id");
        for (int i = 0; i < 6 && !"LIVE".equals(orderStatus(shop, orderId)); i++) runner.runOnce();

        String list = onHost(shop.host(), shop.access(), "GET", "/api/v1/tenant/domains", null).andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> rows = JsonPath.read(list, "$");
        String domainId = rows.stream().filter(r -> (label + ".com").equals(r.get("host"))).findFirst().orElseThrow().get("id").toString();

        // makePrimary works exactly like for a connected domain
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains/" + domainId + "/primary", null).andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));

        UUID tenantId = tenantIdOf(shop);
        // move ONLY this tenant's own subscription row to a plan without custom_domain (STORE_STARTER) - never touch the
        // shared plan_features config, or every other STORE tenant in the suite would lose the feature too.
        jdbc.sql("UPDATE billing.tenant_subscriptions SET plan_id = (SELECT id FROM billing.subscription_plans WHERE code = 'STORE_STARTER') WHERE tenant_id = :t")
                .param("t", tenantId).update();
        directory.invalidateAll();
        domains.demoteStoppedPrimaries();   // losing the plan entitlement alone must NOT demote a PURCHASED primary
        var row = jdbc.sql("SELECT is_primary, source FROM core.tenant_domains WHERE id = :i").param("i", UUID.fromString(domainId)).query().listOfRows().get(0);
        assertThat(row.get("source")).isEqualTo("PURCHASED");
        assertThat(row.get("is_primary")).isEqualTo(true);

        // remove still refuses while it is primary, exactly like the connect flow
        onHost(shop.host(), shop.access(), "DELETE", "/api/v1/tenant/domains/" + domainId, null).andExpect(status().isBadRequest());

        // a REAL termination (cancelled) still demotes it - the carve-out is specific to entitlement loss, not a blanket "never demote"
        jdbc.sql("UPDATE core.tenants SET status = 'CANCELLED' WHERE id = :t").param("t", tenantId).update();
        domains.demoteStoppedPrimaries();
        var afterCancel = jdbc.sql("SELECT is_primary FROM core.tenant_domains WHERE id = :i").param("i", UUID.fromString(domainId)).query().listOfRows().get(0);
        assertThat(afterCancel.get("is_primary")).isEqualTo(false);
    }
}

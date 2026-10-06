package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.core.auth.LoginThrottle;
import com.platform.core.tenant.DnsVerifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TenancyIntegrationTest.FakeDns.class)
class TenancyIntegrationTest extends IntegrationTestBase {

    static final Map<String, List<String>> TXT = new ConcurrentHashMap<>();

    @TestConfiguration
    static class FakeDns {
        @Bean @Primary
        DnsVerifier fakeDns() { return name -> TXT.getOrDefault(name, List.of()); }
    }

    // ---- tests -----------------------------------------------------------------------------

    @Test
    void onboardingGivesInstantSubdomainThatResolvesToItsOwnTenantOnly() throws Exception {
        Tenant store = onboard("STORE");
        Tenant clinic = onboard("CLINIC");

        assertThat(store.host()).isEqualTo(store.slug() + ".platform.test");
        onHost(store.host(), null, "GET", "/api/v1/tenant/context", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("STORE")).andExpect(jsonPath("$.slug").value(store.slug()));
        onHost(clinic.host(), null, "GET", "/api/v1/tenant/context", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("CLINIC"));
        onHost("unknown-" + uniq() + ".platform.test", null, "GET", "/api/v1/tenant/context", null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    @Test
    void tenantIdSentByClientIsIgnoredAndTokenFromOneTenantHasNoPowerOnAnother() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        // owner of A can manage A's domains...
        onHost(a.host(), a.access(), "GET", "/api/v1/tenant/domains", null).andExpect(status().isOk());
        // ...but the same token on B's host has no membership there -> forbidden, not B's data
        onHost(b.host(), a.access(), "GET", "/api/v1/tenant/domains", null).andExpect(status().isForbidden());
        // a forged tenantId param/body changes nothing
        onHost(b.host(), a.access(), "GET", "/api/v1/tenant/domains?tenantId=whatever", null).andExpect(status().isForbidden());
    }

    @Test
    void ownerCannotLogInOnAnotherTenantsHost() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("CLINIC");
        onHost(b.host(), null, "POST", "/api/v1/auth/login",
                "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(a.phone()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
        onHost(a.host(), null, "POST", "/api/v1/auth/login",
                "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(a.phone()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("STORE_OWNER"));
    }

    @Test
    void slugRulesAndDuplicates() throws Exception {
        Tenant a = onboard("STORE");
        mvc.perform(get("/api/v1/onboarding/slug-available").param("slug", a.slug()).header("Host", "platform.test"))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/v1/onboarding/slug-available").param("slug", "admin").header("Host", "platform.test"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLUG_RESERVED"));
        mvc.perform(get("/api/v1/onboarding/slug-available").param("slug", "free" + uniq()).header("Host", "platform.test"))
                .andExpect(jsonPath("$.available").value(true));
        String dup = """
                {"type":"STORE","name":"X","slug":"%s","ownerFirstName":"O","phone":"+201099999991","password":"s3cretPass!"}
                """.formatted(a.slug());
        mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    @Test
    void doctorStaffGetsPinThenCreatesOwnPasswordWithoutOtp() throws Exception {
        Tenant clinic = onboard("CLINIC");
        String phone = nextPhone();
        String res = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"Sara\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(phone))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.needsActivation").value(true))
                .andReturn().getResponse().getContentAsString();
        String pin = JsonPath.read(res, "$.activationPin");

        onHost(clinic.host(), null, "POST", "/api/v1/auth/check-identifier", "{\"identifier\":\"%s\"}".formatted(phone))
                .andExpect(jsonPath("$.next").value("ACTIVATE"));
        // cannot log in without a password yet
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"whatever123\"}".formatted(phone))
                .andExpect(status().isUnauthorized());
        // wrong PIN rejected, right PIN creates the password and signs in
        onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"00000000\",\"newPassword\":\"MyNewPass123\"}".formatted(phone))
                .andExpect(status().isBadRequest());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"MyNewPass123\"}".formatted(phone, pin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("RECEPTIONIST"));
        // PIN is single-use
        onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"Another123456\"}".formatted(phone, pin))
                .andExpect(status().isBadRequest());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"MyNewPass123\"}".formatted(phone))
                .andExpect(status().isOk());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/check-identifier", "{\"identifier\":\"%s\"}".formatted(phone))
                .andExpect(jsonPath("$.next").value("ENTER_PASSWORD"));
    }

    @Test
    void pinIsBurnedAfterTooManyWrongGuessesAndReceptionistCannotManageStaff() throws Exception {
        Tenant clinic = onboard("CLINIC");
        String phone = nextPhone();
        String res = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"Omar\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(phone))
                .andReturn().getResponse().getContentAsString();
        String pin = JsonPath.read(res, "$.activationPin");
        for (int i = 0; i < 5; i++) {
            onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                    "{\"identifier\":\"%s\",\"pin\":\"11111111\",\"newPassword\":\"MyNewPass123\"}".formatted(phone))
                    .andExpect(status().isBadRequest());
        }
        onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"MyNewPass123\"}".formatted(phone, pin))
                .andExpect(status().isBadRequest());

        // the receptionist (re-issued PIN, activated) lacks staff.manage
        String pin2 = JsonPath.read(onHost(clinic.host(), clinic.access(), "POST",
                "/api/v1/tenant/members/%s/activation-pin".formatted(userId(clinic, phone)), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.activationPin");
        String token = JsonPath.read(onHost(clinic.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"MyNewPass123\"}".formatted(phone, pin2))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        onHost(clinic.host(), token, "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"X\",\"phone\":\"01014000001\",\"role\":\"DOCTOR\"}").andExpect(status().isForbidden());
    }

    private String userId(Tenant clinic, String phone) throws Exception {
        // the invite response contains it; look it up through a fresh invite of the same phone (idempotent membership)
        String res = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members",
                "{\"firstName\":\"Omar\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(phone))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.userId");
    }

    @Test
    void customDomainNeedsDnsVerificationBeforeItIsServedOrGetsCertificates() throws Exception {
        Tenant store = onboard("STORE");
        String host = "www.my-" + uniq() + ".com";
        String res = onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"https://%s/\"}".formatted(host.toUpperCase()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.dnsInstructions[0].type").value("TXT"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");
        String txtName = JsonPath.read(res, "$.dnsInstructions[0].name");
        String token = JsonPath.read(res, "$.dnsInstructions[0].value");
        assertThat(JsonPath.<String>read(res, "$.dnsInstructions[1].value")).isEqualTo("edge.platform.test");

        // unverified: not served, no certificate, cannot be primary
        mvc.perform(get("/internal/domains/allowed").param("domain", host).header("Host", "localhost")).andExpect(status().isNotFound());
        onHost(host, null, "GET", "/api/v1/tenant/context", null).andExpect(status().isNotFound());
        onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains/" + id + "/primary", null).andExpect(status().isBadRequest());
        onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains/" + id + "/verify", null).andExpect(jsonPath("$.verified").value(false));

        // owner adds the TXT record -> verified
        TXT.put(txtName, List.of(token));
        onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains/" + id + "/verify", null).andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/internal/domains/allowed").param("domain", host).header("Host", "localhost")).andExpect(status().isOk());
        onHost(host, null, "GET", "/api/v1/tenant/context", null).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(store.slug()));

        // another tenant cannot grab it nor see/verify it
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"%s\"}".formatted(host))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOMAIN_TAKEN"));
        onHost(other.host(), other.access(), "POST", "/api/v1/tenant/domains/" + id + "/verify", null).andExpect(status().isNotFound());

        onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains/" + id + "/primary", null).andExpect(jsonPath("$.primary").value(true));
        // reserved: cannot claim platform names
        onHost(store.host(), store.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"evil.platform.test\"}").andExpect(status().isBadRequest());
    }

    @Test
    void refreshTokenRotatesAndReuseRevokesEverything() throws Exception {
        Tenant t = onboard("STORE");
        String r1 = JsonPath.read(onHost(t.host(), null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(t.refresh()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.refreshToken");
        // old token reuse -> rejected and all sessions killed
        onHost(t.host(), null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(t.refresh())).andExpect(status().isUnauthorized());
        onHost(t.host(), null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(r1)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireAuth() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), null, "GET", "/api/v1/tenant/domains", null).andExpect(status().isUnauthorized());
        onHost(t.host(), null, "GET", "/api/v1/auth/me", null).andExpect(status().isUnauthorized());
        onHost(t.host(), t.access(), "GET", "/api/v1/auth/me", null).andExpect(status().isOk());
    }

    @Test
    void onboardingIsIdempotentWithKey() throws Exception {
        String slug = "idem" + uniq();
        String body = """
                {"type":"STORE","name":"Idem","slug":"%s","ownerFirstName":"O","phone":"%s","password":"s3cretPass!"}
                """.formatted(slug, nextPhone());
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.replay").value(false));
        mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.replay").value(true));
    }

    @Test
    void resolveEndpointTellsTheWebAppWhatIsBehindAHost() throws Exception {
        Tenant clinic = onboard("CLINIC");
        onHost(clinic.host(), null, "GET", "/api/v1/tenant/resolve", null).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("TENANT")).andExpect(jsonPath("$.type").value("CLINIC")).andExpect(jsonPath("$.branding.primary_color").exists());
        onHost("platform.test", null, "GET", "/api/v1/tenant/resolve", null).andExpect(jsonPath("$.kind").value("PLATFORM"));
        onHost("nobody-" + uniq() + ".platform.test", null, "GET", "/api/v1/tenant/resolve", null).andExpect(jsonPath("$.kind").value("UNKNOWN"));
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/auth/me", null).andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.hasItems("patient.read", "billing.manage"))).andExpect(jsonPath("$.roles[0]").exists());
    }

    @Test
    void ownerCanListAndRemoveStaffButNotOwnersOrThemselves() throws Exception {
        Tenant clinic = onboard("CLINIC");
        String phone = nextPhone();
        String inv = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Mai\",\"phone\":\"%s\",\"role\":\"NURSE\"}".formatted(phone)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(inv, "$.userId");
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/tenant/members", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].roles").value("NURSE"));
        String ownerId = JsonPath.read(onHost(clinic.host(), clinic.access(), "GET", "/api/v1/auth/me", null).andReturn().getResponse().getContentAsString(), "$.id");
        onHost(clinic.host(), clinic.access(), "DELETE", "/api/v1/tenant/members/" + ownerId, null).andExpect(status().isBadRequest());
        onHost(clinic.host(), clinic.access(), "DELETE", "/api/v1/tenant/members/" + userId, null).andExpect(status().isNoContent());
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/tenant/members", null).andExpect(jsonPath("$.length()").value(1));
        // another tenant's owner cannot see or remove them
        Tenant other = onboard("CLINIC");
        onHost(other.host(), other.access(), "DELETE", "/api/v1/tenant/members/" + userId, null).andExpect(status().isNotFound());
    }

    @Test
    void brandingAcceptsOnlyValidatedTokensAndShowsInTenantContext() throws Exception {
        Tenant store = onboard("STORE");
        onHost(store.host(), store.access(), "PATCH", "/api/v1/tenant/branding", "{\"primaryColor\":\"red;background:url(x)\"}").andExpect(status().isBadRequest());
        onHost(store.host(), store.access(), "PATCH", "/api/v1/tenant/branding", "{\"primaryColor\":\"#112233\",\"secondaryColor\":\"#aabbcc\",\"locale\":\"en\"}").andExpect(status().isOk()).andExpect(jsonPath("$.primaryColor").value("#112233"));
        onHost(store.host(), null, "GET", "/api/v1/tenant/context", null).andExpect(jsonPath("$.branding.primary_color").value("#112233")).andExpect(jsonPath("$.locale").value("en"));
        onHost(store.host(), null, "PATCH", "/api/v1/tenant/branding", "{\"primaryColor\":\"#000000\"}").andExpect(status().isUnauthorized());
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.BillingService;
import com.platform.files.FileStorage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Plans, custom-domain gating, manual pay requests, the disabled state and the platform owner's tools (change plan, delete everything). */
class SubscriptionLifecycleIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired BillingService billing;
    @Autowired FileStorage storage;

    UUID idOf(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String platformToken(String role) throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = :r AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).param("r", role).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void standardShopCannotUseACustomDomainButProfessionalAndFreeCan() throws Exception {
        Tenant shop = onboard("STORE");   // free trial: everything included
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.free-" + uniq() + ".com\"}").andExpect(status().isCreated());

        billing.activatePlan(idOf(shop), billing.planId("STORE_STANDARD"), 30);
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.std-" + uniq() + ".com\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_AVAILABLE"));

        billing.activatePlan(idOf(shop), billing.planId("STORE_PRO"), 30);
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.pro-" + uniq() + ".com\"}").andExpect(status().isCreated());
    }

    @Test
    void trialBarStartsFiveDaysBeforeTheEnd() throws Exception {
        Tenant clinic = onboard("CLINIC");
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/billing/status", null).andExpect(jsonPath("$.state").value("OK")).andExpect(jsonPath("$.trial").value(true));
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() + interval '4 days 2 hours' WHERE tenant_id = :t").param("t", idOf(clinic)).update();
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/billing/status", null).andExpect(jsonPath("$.state").value("TRIAL_ENDING")).andExpect(jsonPath("$.daysLeft").value(5));
    }

    @Test
    void payRequestNeedsAPhoneIsNotDuplicatedAndPlatformCanActivateIt() throws Exception {
        String admin = platformToken("PLATFORM_OWNER");
        Tenant shop = onboard("STORE");
        // the clinic-only plan is refused for a shop, the free plan cannot be "paid"
        onHost(shop.host(), shop.access(), "POST", "/api/v1/billing/pay-requests", "{\"planCode\":\"CLINIC_PRO\"}").andExpect(status().isBadRequest());
        onHost(shop.host(), shop.access(), "POST", "/api/v1/billing/pay-requests", "{\"planCode\":\"STORE_FREE\"}").andExpect(status().isBadRequest());

        String first = onHost(shop.host(), shop.access(), "POST", "/api/v1/billing/pay-requests", "{\"planCode\":\"STORE_STANDARD\",\"phone\":\"" + shop.phone() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEW")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");
        // pressing again (and choosing the better plan) reuses the one open request
        onHost(shop.host(), shop.access(), "POST", "/api/v1/billing/pay-requests", "{\"planCode\":\"STORE_PRO\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.planCode").value("STORE_PRO")).andExpect(jsonPath("$.amountMinor").value(75000));
        assertThat(jdbc.sql("SELECT count(*) FROM billing.pay_requests WHERE tenant_id = :t").param("t", idOf(shop)).query(Long.class).single()).isEqualTo(1);

        // the platform owner sees it with the owner's contact, marks it paid -> the shop is on Professional for 30 days
        onHost("platform.test", admin, "GET", "/api/v1/platform/pay-requests?status=NEW", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + id + "')].phone").isNotEmpty());
        onHost("platform.test", admin, "GET", "/api/v1/platform/overview", null).andExpect(jsonPath("$.newPayRequests").isNumber());
        onHost("platform.test", shop.access(), "GET", "/api/v1/platform/pay-requests", null).andExpect(status().isForbidden());
        onHost("platform.test", admin, "POST", "/api/v1/platform/pay-requests/" + id, "{\"status\":\"PAID\",\"adminNote\":\"cash\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        onHost(shop.host(), shop.access(), "GET", "/api/v1/billing/subscription", null).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.planCode").value("STORE_PRO"));
    }

    @Test
    void platformOwnerChangesPlanReactivatesAndPatientsAreRefusedWhileDisabled() throws Exception {
        String admin = platformToken("PLATFORM_ADMIN");
        Tenant clinic = onboard("CLINIC");
        PatientLogin patient = patientAt(clinic, "Mona");
        String id = idOf(clinic).toString();

        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '1 hour' WHERE tenant_id = :t").param("t", idOf(clinic)).update();
        billing.expiryCheck();
        // the patient cannot sign in to a disabled clinic, the owner can (to pay)
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"" + patient.phone() + "\",\"password\":\"PatientPass1\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"" + clinic.phone() + "\",\"password\":\"s3cretPass!\"}").andExpect(status().isOk());
        onHost(clinic.host(), patient.token(), "GET", "/api/v1/portal/me", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));

        // the admin gives the clinic Professional for 30 days: it works again
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/plan", "{\"planCode\":\"STORE_PRO\"}").andExpect(status().isBadRequest());
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/plan", "{\"planCode\":\"CLINIC_PRO\",\"days\":30}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.subscription.planCode").value("CLINIC_PRO"));
        onHost(clinic.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"" + patient.phone() + "\",\"password\":\"PatientPass1\"}").andExpect(status().isOk());
        // a platform admin cannot delete businesses, only the platform owner can
        onHost("platform.test", admin, "DELETE", "/api/v1/platform/tenants/" + id, "{\"confirmSlug\":\"" + clinic.slug() + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void purgeRemovesTheBusinessAndOnlyThePeopleWhoBelongedToItAlone() throws Exception {
        String owner = platformToken("PLATFORM_OWNER");
        Tenant a = onboard("CLINIC");
        Tenant b = onboard("CLINIC");
        String shared = nextPhone();
        patientAt(a, "Shared", shared);
        onHost(b.host(), b.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Shared\",\"phone\":\"" + shared + "\"}").andExpect(status().isCreated());
        String soloPhone = nextPhone();
        patientAt(a, "Solo", soloPhone);
        UUID idA = idOf(a), idB = idOf(b);
        UUID ownerA = jdbc.sql("SELECT id FROM core.users WHERE phone = :p").param("p", a.phone()).query(UUID.class).single();
        storage.put(FileStorage.Bucket.PRIVATE, "tenants/" + idA + "/test/x.txt", new byte[] {1, 2, 3}, "text/plain", null);

        onHost("platform.test", owner, "DELETE", "/api/v1/platform/tenants/" + idA, "{\"confirmSlug\":\"wrong\"}").andExpect(status().isBadRequest());
        onHost("platform.test", owner, "DELETE", "/api/v1/platform/tenants/" + idA, "{\"confirmSlug\":\"" + a.slug() + "\"}").andExpect(status().isOk());

        // nothing of tenant A is left in any table
        for (String table : jdbc.sql("SELECT table_schema || '.' || table_name FROM information_schema.columns WHERE column_name = 'tenant_id' AND table_schema NOT IN ('information_schema','pg_catalog')").query(String.class).list()) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", idA).query(Long.class).single()).as(table).isZero();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t").param("t", idA).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM core.users WHERE id = :u").param("u", ownerA).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE phone = :p").param("p", soloPhone).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE phone = :p").param("p", shared).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t").param("t", idB).query(Long.class).single()).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> storage.get(FileStorage.Bucket.PRIVATE, "tenants/" + idA + "/test/x.txt"));
        // the other clinic and its patient still work
        onHost(b.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"" + b.phone() + "\",\"password\":\"s3cretPass!\"}").andExpect(status().isOk());
        onHost(a.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isNotFound());
    }

    @Test
    void yearlyPlanGivesTwoMonthsFreeAndActivatesForAYear() throws Exception {
        String admin = platformToken("PLATFORM_OWNER");
        Tenant shop = onboard("STORE");
        onHost(shop.host(), null, "GET", "/api/v1/billing/plans", null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='STORE_STANDARD_YEARLY')].priceMinor").value(500000))
                .andExpect(jsonPath("$[?(@.code=='STORE_PRO_YEARLY')].priceMinor").value(750000));
        String req = onHost(shop.host(), shop.access(), "POST", "/api/v1/billing/pay-requests", "{\"planCode\":\"STORE_PRO_YEARLY\",\"phone\":\"" + shop.phone() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountMinor").value(750000)).andReturn().getResponse().getContentAsString();
        onHost("platform.test", admin, "POST", "/api/v1/platform/pay-requests/" + JsonPath.read(req, "$.id"), "{\"status\":\"PAID\"}").andExpect(status().isOk());
        long days = jdbc.sql("SELECT extract(day FROM (current_period_end - now()))::bigint FROM billing.tenant_subscriptions WHERE tenant_id = :t").param("t", idOf(shop)).query(Long.class).single();
        assertThat(days).isBetween(363L, 365L);
        // yearly Professional keeps the custom domain, like the monthly one
        onHost(shop.host(), shop.access(), "POST", "/api/v1/tenant/domains", "{\"host\":\"www.yr-" + uniq() + ".com\"}").andExpect(status().isCreated());
    }

    @Test
    void platformOwnerHasFullControlOverABusiness() throws Exception {
        String owner = platformToken("PLATFORM_OWNER");
        String admin = platformToken("PLATFORM_ADMIN");
        Tenant clinic = onboard("CLINIC");
        String id = idOf(clinic).toString();

        // reset the owner's password with a one-time PIN, used once on the sign-in "forgot password" step
        String pin = JsonPath.read(onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/owner/reset-password", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.pin");
        onHost(clinic.host(), null, "POST", "/api/v1/auth/reset-password", "{\"identifier\":\"" + clinic.phone() + "\",\"pin\":\"" + pin + "\",\"newPassword\":\"BrandNewPass9\"}").andExpect(status().isOk());
        onHost(clinic.host(), null, "POST", "/api/v1/auth/reset-password", "{\"identifier\":\"" + clinic.phone() + "\",\"pin\":\"" + pin + "\",\"newPassword\":\"AnotherPass9\"}").andExpect(status().isBadRequest());

        // fix the owner's contact; a number that belongs to someone else is refused
        String newPhone = nextPhone();
        onHost("platform.test", admin, "PATCH", "/api/v1/platform/tenants/" + id + "/owner", "{\"phone\":\"" + newPhone + "\",\"email\":\"owner" + uniq() + "@example.com\"}").andExpect(status().isOk()).andExpect(jsonPath("$.owner.phone").value(newPhone));
        Tenant other = onboard("STORE");
        onHost("platform.test", admin, "PATCH", "/api/v1/platform/tenants/" + id + "/owner", "{\"phone\":\"" + other.phone() + "\"}").andExpect(status().isConflict());

        // rename: the new address works, the old one stops
        String newSlug = "renamed" + uniq().substring(0, 6);
        onHost("platform.test", admin, "PATCH", "/api/v1/platform/tenants/" + id, "{\"name\":\"Clinic Renamed\",\"slug\":\"" + newSlug + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Clinic Renamed")).andExpect(jsonPath("$.slug").value(newSlug));
        String newHost = clinic.host().replace(clinic.slug(), newSlug);
        onHost(newHost, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk());
        onHost(clinic.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isNotFound());

        // support access: only the platform owner can open it, and it is audited; an ordinary admin cannot
        onHost("platform.test", admin, "POST", "/api/v1/platform/tenants/" + id + "/support-access", null).andExpect(status().isForbidden());
        String support = onHost("platform.test", owner, "POST", "/api/v1/platform/tenants/" + id + "/support-access", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(support, "$.ticket")).hasSizeGreaterThan(20);
        onHost(newHost, owner, "GET", "/api/v1/clinic/patients", null).andExpect(status().isOk());      // acts as the owner on that business
        onHost(newHost, admin, "GET", "/api/v1/clinic/patients", null).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'SUPPORT_ACCESS' AND tenant_id = :t").param("t", UUID.fromString(id)).query(Long.class).single()).isEqualTo(1);
    }
}

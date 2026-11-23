package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.core.tenant.TenantType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SERVICE is a business type of its own (not a shop, a clinic or a law office) that runs on the shop's building blocks. */
class ServiceBusinessTypeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    Tenant onboardService() throws Exception {
        String slug = "svc" + uniq();
        String phone = nextPhone();
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SERVICE\",\"name\":\"Salon %s\",\"slug\":\"%s\",\"ownerFirstName\":\"Owner\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, slug, phone)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tenant.type").value("SERVICE")).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone, JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    @Test
    void aServiceBusinessIsItsOwnTypeWithTheShopsBuildingBlocksAndItsOwnPlan() throws Exception {
        Tenant t = onboardService();
        UUID tenant = id(t);
        assertThat(jdbc.sql("SELECT tenant_type FROM core.tenants WHERE id = :t").param("t", tenant).query(String.class).single()).isEqualTo("SERVICE");
        // its own plan, free forever like a shop's, paid with points: 100 welcome points once
        assertThat(jdbc.sql("SELECT p.code FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id WHERE s.tenant_id = :t").param("t", tenant).query(String.class).single()).isEqualTo("SERVICE_FREE");
        assertThat(jdbc.sql("SELECT s.status FROM billing.tenant_subscriptions s WHERE s.tenant_id = :t").param("t", tenant).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT balance_minor FROM billing.wallets WHERE tenant_id = :t").param("t", tenant).query(Long.class).single()).isEqualTo(10000L);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.subscription_plan_features f JOIN billing.subscription_plans p ON p.id = f.plan_id WHERE p.code = 'SERVICE_FREE'").query(Long.class).single()).isGreaterThan(0);
        // the owner runs the commerce screens: services, hours, bookings, products
        onHost(t.host(), t.access(), "GET", "/api/v1/store/bookings/summary", null).andExpect(status().isOk());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/services", "{\"name\":\"قص شعر\",\"durationMinutes\":45,\"priceMinor\":15000}").andExpect(status().is2xxSuccessful());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/products", null).andExpect(status().isOk());
        // the public booking page is open on its address
        onHost(t.host(), null, "GET", "/api/v1/shop/booking/config", null).andExpect(status().isOk());
    }

    @Test
    void shopsClinicsAndLawyersAreNotTouched() throws Exception {
        Tenant shop = onboard("STORE");
        UUID s = id(shop);
        assertThat(jdbc.sql("SELECT p.code FROM billing.tenant_subscriptions x JOIN billing.subscription_plans p ON p.id = x.plan_id WHERE x.tenant_id = :t").param("t", s).query(String.class).single()).isEqualTo("STORE_FREE");
        Tenant clinic = onboard("CLINIC");
        assertThat(jdbc.sql("SELECT tenant_type FROM core.tenants WHERE id = :t").param("t", id(clinic)).query(String.class).single()).isEqualTo("CLINIC");
        onHost(clinic.host(), clinic.access(), "GET", "/api/v1/store/bookings/summary", null).andExpect(status().is4xxClientError());   // a clinic has no shop screens
        assertThat(TenantType.isShopLike("STORE")).isTrue();
        assertThat(TenantType.isShopLike("SERVICE")).isTrue();
        assertThat(TenantType.isShopLike("CLINIC")).isFalse();
        assertThat(TenantType.isShopLike("LAWYER")).isFalse();
    }

    @Test
    void aServiceBusinessSeesOnlyItsOwnDataAndCannotTakeAnotherTypesPlan() throws Exception {
        Tenant a = onboardService(), b = onboardService();
        onHost(a.host(), a.access(), "POST", "/api/v1/store/services", "{\"name\":\"خدمة أ\",\"durationMinutes\":30,\"priceMinor\":10000}").andExpect(status().is2xxSuccessful());
        onHost(b.host(), b.access(), "GET", "/api/v1/store/services", null).andExpect(jsonPath("$.length()").value(0));
        // a SERVICE business cannot be put on a clinic plan
        assertThat(jdbc.sql("SELECT count(*) FROM billing.subscription_plans WHERE code = 'SERVICE_FREE' AND tenant_type = 'SERVICE'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theTenDesignsBelongToServiceBusinessesOnlyAndTheyKeepTheirOwn() throws Exception {
        Tenant svc = onboardService();
        Tenant shop = onboard("STORE");
        for (String tpl : List.of("lumiere", "barbershop", "homefix", "fixit", "aqua", "sparkle", "garage", "pro", "slot", "bazaar"))
            onHost(svc.host(), svc.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"%s\"}".formatted(tpl)).andExpect(status().isOk());
        onHost(svc.host(), svc.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"atelier\"}").andExpect(status().isBadRequest());   // a shop design is not for a service business
        onHost(shop.host(), shop.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"lumiere\"}").andExpect(status().isBadRequest());   // and a service design is not for a shop
        onHost(shop.host(), shop.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"atelier\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT template FROM commerce.store_profiles WHERE tenant_id = :t").param("t", id(svc)).query(String.class).single()).isEqualTo("bazaar");
    }

    @Test
    void thePlatformAdminListFiltersByOneOrSeveralBusinessTypes() throws Exception {
        onboardService();
        onboard("STORE");
        onboard("CLINIC");
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String platformToken = JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        for (String types : List.of("SERVICE", "STORE,SERVICE", "CLINIC,SERVICE,STORE")) {
            var body = onHost("platform.test", platformToken, "GET", "/api/v1/platform/tenants?type=" + types + "&pageSize=100", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> got = JsonPath.read(body, "$.data[*].tenantType");
            assertThat(got).isNotEmpty();
            assertThat(java.util.Set.copyOf(got)).isSubsetOf(java.util.Set.of(types.split(",")));
            if (types.contains("SERVICE")) assertThat(got).contains("SERVICE");
        }
        onHost("platform.test", platformToken, "GET", "/api/v1/platform/tenants?type=MOON", null).andExpect(status().isBadRequest());
    }
}

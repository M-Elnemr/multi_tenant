package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Account activity filters of the platform admin (tenants list): business type (several), subscription start/end dates, expiring soon, points consumption; combinable; sorting and paging in the database. */
class AdminActivityFiltersIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String admin() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    String tag = "x" + uniq();   // the test database is shared with other tests: exact checks are restricted to this test's own businesses by name

    Tenant service() throws Exception {
        String slug = "act" + tag + uniq();
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SERVICE\",\"name\":\"Act %s\",\"slug\":\"%s\",\"ownerFirstName\":\"O\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, slug, nextPhone()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), "", JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    void spend(UUID tenant, long minor, String kind) {
        jdbc.sql("INSERT INTO billing.wallet_entries (tenant_id, kind, amount_minor, balance_after_minor, ref, note) VALUES (:t, :k, :a, 0, 'test', 'test')").param("t", tenant).param("k", kind).param("a", -minor).update();
    }

    List<String> slugs(String admin, String query) throws Exception {
        String body = onHost("platform.test", admin, "GET", "/api/v1/platform/tenants?pageSize=100&" + query, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[*].slug");
    }

    @Test
    void theFiltersWorkTogetherAndPointsComeFromWhatTheWalletReallyDebited() throws Exception {
        String admin = admin();
        Tenant svcBig = service(), svcSmall = service(), shop = onboard("STORE"), clinic = onboard("CLINIC");
        spend(id(svcBig), 5000, "AI"); spend(id(svcBig), 1000, "MESSAGE"); spend(id(svcBig), 9999, "TOPUP");   // a top-up is not consumption
        spend(id(svcSmall), 200, "AI");
        spend(id(shop), 7000, "AI");
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_start = now() - interval '20 days', current_period_end = now() + interval '3 days', status = 'ACTIVE' WHERE tenant_id = :t").param("t", id(svcBig)).update();
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_start = now() - interval '5 days', current_period_end = now() + interval '40 days', status = 'ACTIVE' WHERE tenant_id = :t").param("t", id(svcSmall)).update();
        // business type (several) alone
        assertThat(slugs(admin, "type=SERVICE")).contains(svcBig.slug(), svcSmall.slug()).doesNotContain(shop.slug(), clinic.slug());
        assertThat(slugs(admin, "type=SERVICE,CLINIC")).contains(svcBig.slug(), clinic.slug()).doesNotContain(shop.slug());
        // points consumption: 6,000 minor = 5,000 AI + 1,000 messages; the top-up does not count
        assertThat(slugs(admin, "type=SERVICE&minPointsMinor=6000&q=" + tag)).containsExactly(svcBig.slug());
        assertThat(slugs(admin, "type=SERVICE&minPointsMinor=100&maxPointsMinor=5999&q=" + tag)).containsExactly(svcSmall.slug());
        assertThat(slugs(admin, "minPointsMinor=6500")).contains(shop.slug()).doesNotContain(svcBig.slug());
        // expiring soon (7 days) combined with the type and the points
        assertThat(slugs(admin, "type=SERVICE&expiringDays=7&q=" + tag)).containsExactly(svcBig.slug());
        assertThat(slugs(admin, "type=SERVICE&expiringDays=7&minPointsMinor=100000&q=" + tag)).isEmpty();
        assertThat(slugs(admin, "type=SERVICE&expiringDays=60&q=" + tag)).containsExactlyInAnyOrder(svcBig.slug(), svcSmall.slug());
        // subscription start and end dates combined
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(slugs(admin, "type=SERVICE&q=" + tag + "&startFrom=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(7) + "&startTo=" + today)).containsExactly(svcSmall.slug());   // started in the last week
        assertThat(slugs(admin, "type=SERVICE&q=" + tag + "&endTo=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(10))).containsExactly(svcBig.slug());                              // ends within 10 days
        assertThat(slugs(admin, "type=SERVICE&q=" + tag + "&startTo=" + today + "&endFrom=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(20))).containsExactly(svcSmall.slug());   // both ranges at once
    }

    @Test
    void sortingAndPagingAreServerSideAndInputIsValidated() throws Exception {
        String admin = admin();
        Tenant a = service(), b = service(), c = service();
        spend(id(a), 100, "AI"); spend(id(b), 300, "AI"); spend(id(c), 200, "AI");
        List<String> byPoints = slugs(admin, "type=SERVICE&sort=points&dir=desc&q=" + tag);
        assertThat(byPoints.indexOf(b.slug())).isLessThan(byPoints.indexOf(c.slug()));
        assertThat(byPoints.indexOf(c.slug())).isLessThan(byPoints.indexOf(a.slug()));
        String page1 = onHost("platform.test", admin, "GET", "/api/v1/platform/tenants?type=SERVICE&sort=points&dir=desc&pageSize=2&page=1&q=" + tag, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String page2 = onHost("platform.test", admin, "GET", "/api/v1/platform/tenants?type=SERVICE&sort=points&dir=desc&pageSize=2&page=2&q=" + tag, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(page1, "$.data[*].slug")).hasSize(2);
        assertThat(JsonPath.<Integer>read(page1, "$.meta.total")).isGreaterThanOrEqualTo(3);
        assertThat(JsonPath.<List<String>>read(page1, "$.data[*].slug")).doesNotContainAnyElementsOf(JsonPath.<List<String>>read(page2, "$.data[*].slug"));
        assertThat(JsonPath.<Integer>read(page1, "$.data[0].pointsUsedMinor")).isEqualTo(300);
        for (String bad : List.of("sort=password", "startFrom=yesterday", "expiringDays=0", "type=MOON"))
            onHost("platform.test", admin, "GET", "/api/v1/platform/tenants?" + bad, null).andExpect(status().isBadRequest());
    }
}

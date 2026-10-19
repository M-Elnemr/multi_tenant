package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class VisitorAnalyticsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    static final String CHROME = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0 Mobile/15E148 Safari/604.1";

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    String view(UUID id, UUID visitor, UUID session, String path, String ua) {
        return "{\"type\":\"view\",\"id\":\"%s\",\"visitorId\":\"%s\",\"sessionId\":\"%s\",\"path\":\"%s\",\"referrer\":\"https://google.com/\",\"language\":\"ar-EG\",\"timezone\":\"Africa/Cairo\",\"screenW\":390,\"screenH\":844,\"userAgent\":\"%s\"}"
                .formatted(id, visitor, session, path, ua);
    }

    @Test
    void recordsAnonymousViewsIgnoresBotsAndPrivateAreasAndOnlyAdminsRead() throws Exception {
        Tenant shop = onboard("STORE");
        UUID visitor = UUID.randomUUID(), session = UUID.randomUUID(), first = UUID.randomUUID();
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", view(first, visitor, session, "/products/shirt", CHROME)).andExpect(status().isNoContent());
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", view(UUID.randomUUID(), visitor, session, "/cart", CHROME)).andExpect(status().isNoContent());
        // ignored: signed-in areas and bots
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", view(UUID.randomUUID(), visitor, session, "/dashboard/orders", CHROME)).andExpect(status().isNoContent());
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", view(UUID.randomUUID(), UUID.randomUUID(), session, "/", "Googlebot/2.1")).andExpect(status().isNoContent());
        // time spent; a different browser cannot set it
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", "{\"type\":\"leave\",\"id\":\"%s\",\"visitorId\":\"%s\",\"durationMs\":42000}".formatted(first, visitor)).andExpect(status().isNoContent());
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", "{\"type\":\"leave\",\"id\":\"%s\",\"visitorId\":\"%s\",\"durationMs\":1}".formatted(first, UUID.randomUUID())).andExpect(status().isNoContent());
        // garbage is dropped quietly
        onHost(shop.host(), null, "POST", "/api/v1/analytics/collect", "{\"type\":\"view\",\"id\":\"nope\"}").andExpect(status().isNoContent());

        String admin = platformToken();
        String tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(String.class).single();
        onHost("platform.test", admin, "GET", "/api/v1/platform/analytics/visitors?tenantId=" + tenantId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].pageViews").value(2))
                .andExpect(jsonPath("$.data[0].deviceType").value("mobile")).andExpect(jsonPath("$.data[0].os").value("iOS"))
                .andExpect(jsonPath("$.data[0].browser").value("Chrome")).andExpect(jsonPath("$.data[0].totalMs").value(42000));
        onHost("platform.test", admin, "GET", "/api/v1/platform/analytics/visitors/" + visitor, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.views.length()").value(2)).andExpect(jsonPath("$.views[1].path").value("/products/shirt")).andExpect(jsonPath("$.views[1].durationMs").value(42000))
                .andExpect(jsonPath("$.views[1].timezone").value("Africa/Cairo"));
        onHost("platform.test", admin, "GET", "/api/v1/platform/analytics/summary", null).andExpect(status().isOk()).andExpect(jsonPath("$.totals.pageViews").isNumber());

        // not for tenant owners or the public
        onHost(shop.host(), shop.access(), "GET", "/api/v1/platform/analytics/visitors", null).andExpect(status().isForbidden());
        onHost("platform.test", null, "GET", "/api/v1/platform/analytics/summary", null).andExpect(status().isUnauthorized());
    }
}

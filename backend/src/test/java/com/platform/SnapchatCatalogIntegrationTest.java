package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.integrations.SnapchatService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Snapchat as the official documentation allows it (login, catalog, scheduled product feed). Evidence level: MOCK of Snap's documented answers, not a live Snap account.
 * The test also pins what is NOT offered: no customer chat.
 */
class SnapchatCatalogIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ACC = "https://accounts.snapchat.com", ADS = "https://adsapi.snapchat.com";
    @Autowired JdbcClient jdbc;
    @Autowired SnapchatService snap;

    @BeforeEach
    void clean() { FakeHttpGateway.reset(); }

    UUID tid(Tenant t) { return jdbc.sql("SELECT tenant_id FROM core.tenant_domains WHERE host = :h").param("h", t.host()).query(UUID.class).single(); }

    String state(Tenant t) {
        String url = snap.startUrl(tid(t));
        String q = url.substring(url.indexOf("state=") + 6);
        return URLDecoder.decode(q.contains("&") ? q.substring(0, q.indexOf('&')) : q, StandardCharsets.UTF_8);
    }

    String callback(Tenant t, String code) throws Exception {
        var req = get("/api/v1/chat/snapchat/callback").header("Host", "platform.test").param("state", state(t));
        if (code != null) req.param("code", code);
        return mvc.perform(req).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
    }

    void login(String orgs) {
        FakeHttpGateway.when("POST", "/login/oauth2/access_token", 200, "{\"access_token\":\"AT-1\",\"refresh_token\":\"RT-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        FakeHttpGateway.when("GET", "/v1/me/organizations", 200, orgs);
    }

    static String org(String id, String name) { return "{\"sub_request_status\":\"SUCCESS\",\"organization\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}}"; }

    @Test
    void theLoginIsExchangedAsSnapDocumentsAndOneOrganizationIsChosenAutomatically() throws Exception {
        Tenant t = onboard("STORE");
        login("{\"request_status\":\"SUCCESS\",\"organizations\":[" + org("org-1", "Acme") + "]}");
        assertThat(callback(t, "authcode")).isEqualTo("https://" + t.host() + "/dashboard/channels/connect?connected=SNAPCHAT");
        var form = FakeHttpGateway.calls("POST", "/login/oauth2/access_token").get(0).body();
        assertThat(form).contains("grant_type=authorization_code").contains("code=authcode").contains("client_id=snapid").contains("client_secret=snapsecret").contains("redirect_uri=");
        String url = snap.startUrl(tid(t));
        assertThat(url).startsWith(ACC + "/login/oauth2/authorize?response_type=code&client_id=snapid").contains("scope=snapchat-marketing-api");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/snapchat", null)
                .andExpect(jsonPath("$.state").value("CONNECTED")).andExpect(jsonPath("$.organizationId").value("org-1"))
                .andExpect(jsonPath("$.customerChat").value(false));   // never implied
        assertThat(jdbc.sql("SELECT token_enc FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tid(t)).query(String.class).single()).doesNotContain("AT-1").doesNotContain("RT-1");
    }

    @Test
    void severalOrganizationsMeanTheShopMustChooseAndAForeignOneIsRefused() throws Exception {
        Tenant t = onboard("STORE");
        login("{\"organizations\":[" + org("org-1", "A") + "," + org("org-2", "B") + "]}");
        callback(t, "authcode");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/snapchat", null).andExpect(jsonPath("$.state").value("CHOOSE_ORGANIZATION")).andExpect(jsonPath("$.organizations.length()").value(2));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/catalog", "{}").andExpect(status().isBadRequest());   // no organization yet
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/organization", "{\"organizationId\":\"someone-elses\"}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/organization", "{\"organizationId\":\"org-2\"}").andExpect(status().isOk()).andExpect(jsonPath("$.organizationName").value("B"));
    }

    @Test
    void theCatalogAndTheScheduledFeedAreCreatedOnceFromTheShopsOwnFeedAddress() throws Exception {
        Tenant t = onboard("STORE");
        login("{\"organizations\":[" + org("org-1", "Acme") + "]}");
        FakeHttpGateway.when("POST", "/v1/organizations/org-1/catalogs", 200, "{\"request_status\":\"SUCCESS\",\"catalogs\":[{\"sub_request_status\":\"SUCCESS\",\"catalog\":{\"id\":\"cat-1\"}}]}");
        FakeHttpGateway.when("POST", "/v1/catalogs/cat-1/product_feeds", 200, "{\"request_status\":\"SUCCESS\",\"product_feeds\":[{\"sub_request_status\":\"SUCCESS\",\"product_feed\":{\"id\":\"feed-1\"}}]}");
        FakeHttpGateway.when("GET", "/v1/product_feeds/feed-1/feed_uploads", 200, "{\"feed_uploads\":[{\"feed_upload\":{\"status\":\"COMPLETE\",\"updated_at\":\"2026-11-20T10:00:00Z\",\"summary\":{\"total_items\":16,\"items_errored\":1}}}]}");
        callback(t, "authcode");
        String res = onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/catalog", "{}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(res, "$.catalogId")).isEqualTo("cat-1");
        assertThat(JsonPath.<String>read(res, "$.lastUpload.status")).isEqualTo("COMPLETE");
        assertThat(JsonPath.<Integer>read(res, "$.lastUpload.itemsErrored")).isEqualTo(1);
        JsonNode feed = JSON.readTree(FakeHttpGateway.calls("POST", "/v1/catalogs/cat-1/product_feeds").get(0).body()).path("product_feeds").path(0);
        assertThat(feed.path("schedule").path("url").asText()).isEqualTo("https://" + t.host() + "/feeds/facebook.csv");
        assertThat(feed.path("schedule").path("interval_type").asText()).isEqualTo("HOURLY");
        assertThat(feed.path("default_currency").asText()).hasSize(3);
        assertThat(FakeHttpGateway.calls("POST", "/v1/organizations/org-1/catalogs").get(0).headers().get("Authorization")).isEqualTo("Bearer AT-1");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/catalog", "{}").andExpect(status().isOk());   // again: nothing is created twice
        assertThat(FakeHttpGateway.calls("POST", "/v1/organizations/org-1/catalogs")).hasSize(1);
        assertThat(FakeHttpGateway.calls("POST", "/v1/catalogs/cat-1/product_feeds")).hasSize(1);
    }

    @Test
    void aLoginThatIsNotAnOrganizationAdminIsToldSoAndNothingIsMarkedConnected() throws Exception {
        Tenant t = onboard("STORE");
        login("{\"organizations\":[" + org("org-1", "Acme") + "]}");
        FakeHttpGateway.when("POST", "/v1/organizations/org-1/catalogs", 403, "{\"request_status\":\"ERROR\",\"display_message\":\"forbidden\"}");
        callback(t, "authcode");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/snapchat/catalog", "{}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/snapchat", null).andExpect(jsonPath("$.state").value("APPROVAL_REQUIRED")).andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("Organization Admin")));
    }

    @Test
    void aRefusedLoginAndATamperedStateConnectNothing() throws Exception {
        Tenant t = onboard("STORE");
        assertThat(callback(t, null)).endsWith("?error=denied");
        mvc.perform(get("/api/v1/chat/snapchat/callback").header("Host", "platform.test").param("state", "forged.value").param("code", "x")).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.snapchat_links WHERE tenant_id = :t").param("t", tid(t)).query(Integer.class).single()).isZero();
    }

    @Test
    void anotherShopNeverSeesThisShopsSnapchatConnection() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        login("{\"organizations\":[" + org("org-1", "Acme") + "]}");
        callback(a, "authcode");
        onHost(b.host(), b.access(), "GET", "/api/v1/store/integrations/snapchat", null).andExpect(jsonPath("$.state").value("NOT_CONNECTED"));
    }
}

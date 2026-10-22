package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** "Connect with Facebook": the signed state, the callback, choosing what to connect. Facebook itself is faked. */
class MetaLoginIntegrationTest extends IntegrationTestBase {
    @BeforeEach
    void clean() { FakeMetaGraph.reset(); FakeMetaGraph.PAGE_ID = "5" + uniq().replaceAll("[^0-9]", "7") + "1111"; FakeMetaGraph.IG_ID = "6" + uniq().replaceAll("[^0-9]", "8") + "2222"; }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String state(Tenant t, String token) throws Exception {
        String url = JsonPath.read(body(onHost(t.host(), token, "POST", "/api/v1/store/ai/meta/start", null).andExpect(status().isOk())), "$.url");
        assertThat(url).startsWith("https://www.facebook.com/").contains("client_id=testappid").contains("pages_messaging").contains("redirect_uri=");
        String raw = url.substring(url.indexOf("state=") + 6);
        return URLDecoder.decode(raw.substring(0, raw.indexOf('&')), StandardCharsets.UTF_8);
    }

    ResultActions callback(String code, String state) throws Exception {
        return mvc.perform(get("/api/v1/chat/meta/callback").header("Host", "platform.test").param("code", code).param("state", state));
    }

    @Test
    void ownerLogsInPicksTheirPageAndInstagramAndTheyAreConnected() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/meta/available", null).andExpect(jsonPath("$.available").value(true));
        String location = callback("goodcode", state(t, t.access())).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("https://" + t.host() + "/dashboard/ai?connect=");
        String id = location.substring(location.indexOf("connect=") + 8);

        String found = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/meta/pending/" + id, null).andExpect(status().isOk()));
        assertThat(found).contains("Shop Page").contains("@shop_ig").doesNotContain("TOKEN");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/meta/pending/" + id + "/connect", "{\"keys\":[]}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/meta/pending/" + id + "/connect",
                "{\"keys\":[\"FACEBOOK:%s\",\"INSTAGRAM:%s\"]}".formatted(FakeMetaGraph.PAGE_ID, FakeMetaGraph.IG_ID)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/channels", null).andExpect(jsonPath("$.length()").value(2));
        // both were subscribed to webhooks with the page token; the list is single-use
        assertThat(FakeMetaGraph.SENT).extracting(FakeMetaGraph.Sent::path).contains(FakeMetaGraph.PAGE_ID + "/subscribed_apps");
        assertThat(FakeMetaGraph.SENT).extracting(FakeMetaGraph.Sent::token).contains("PAGE-TOKEN-0123456789012");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/meta/pending/" + id, null).andExpect(status().isNotFound());
    }

    @Test
    void forgedStateAndOtherPeopleCannotUseTheLogin() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        String good = state(a, a.access());
        callback("goodcode", good.substring(0, good.length() - 3) + "AAA").andExpect(status().isBadRequest());
        callback("goodcode", "garbage").andExpect(status().isBadRequest());
        // a failed exchange and a cancelled login send the owner back with an error flag instead of an exception page
        assertThat(callback("badcode", state(a, a.access())).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location")).endsWith("/dashboard/ai?metaError=failed");
        assertThat(callback("", state(a, a.access())).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location")).endsWith("/dashboard/ai?metaError=denied");

        String location = callback("goodcode", good).andReturn().getResponse().getHeader("Location");
        String id = location.substring(location.indexOf("connect=") + 8);
        // another shop (even its owner) cannot read or use the list; an employee without AI access cannot start the login
        onHost(b.host(), b.access(), "GET", "/api/v1/store/ai/meta/pending/" + id, null).andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/meta/pending/" + id + "/connect", "{\"keys\":[\"FACEBOOK:" + FakeMetaGraph.PAGE_ID + "\"]}").andExpect(status().isNotFound());
        onHost(b.host(), null, "POST", "/api/v1/store/ai/meta/start", null).andExpect(status().isUnauthorized());
    }

    @Test
    void facebookLoginForBusinessAppsSendAConfigurationIdInsteadOfPermissions() {
        var withConfig = new com.platform.chat.MetaOAuthService(null, null, null, null, null, "app1", "secret", "platform.test", "v21.0", "98765");
        String url = withConfig.startUrl(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        assertThat(url).contains("config_id=98765").contains("override_default_response_type=true").doesNotContain("scope=");
        var plain = new com.platform.chat.MetaOAuthService(null, null, null, null, null, "app1", "secret", "platform.test", "v21.0", "");
        assertThat(plain.startUrl(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())).contains("scope=").doesNotContain("config_id");
    }
}

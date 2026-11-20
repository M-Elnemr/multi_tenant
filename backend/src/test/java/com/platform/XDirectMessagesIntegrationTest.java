package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.TokenCrypto;
import com.platform.chat.XService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** X direct messages: the owner's login (OAuth 2.0 + PKCE), the webhook (CRC check, signed events) and the assistant answering through the DM API. X and the model are faked. */
class XDirectMessagesIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired JdbcClient jdbc;
    @Autowired XService x;
    @Autowired TokenCrypto crypto;

    @BeforeEach
    void clean() {
        FakeAiProvider.reset();
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/2/oauth2/token", 200, "{\"access_token\":\"ACCESS-1\",\"refresh_token\":\"REFRESH-1\",\"expires_in\":7200}");
        FakeHttpGateway.when("GET", "/2/users/me", 200, "{\"data\":{\"id\":\"%s\",\"username\":\"shopx\",\"name\":\"Shop\"}}".formatted(shopX));
        FakeHttpGateway.when("POST", "/2/activity/subscriptions", 200, "{\"data\":{}}");
        FakeHttpGateway.when("POST", "/2/dm_conversations/with/", 200, "{\"data\":{\"dm_conversation_id\":\"c1\",\"dm_event_id\":\"e1\"}}");
        FakeHttpGateway.when("POST", "/2/webhooks", 200, "{\"data\":{\"id\":\"wh-77\",\"url\":\"u\",\"valid\":true}}");
        jdbc.sql("DELETE FROM chat.social_config WHERE platform = 'X'").update();
    }

    String shopX = "9" + (100000000L + (System.nanoTime() % 899999999L));

    static byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("xclientsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    static String sign(String raw) throws Exception { return "sha256=" + Base64.getEncoder().encodeToString(hmac(raw)); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String tid(Tenant t) { return jdbc.sql("SELECT tenant_id::text FROM core.tenant_domains WHERE host = :h").param("h", t.host()).query(String.class).single(); }

    String state(Tenant t) throws Exception {
        String url = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/x/start", null).andExpect(status().isOk())), "$.url");
        assertThat(url).startsWith("https://x.com/i/oauth2/authorize?").contains("client_id=xclientid").contains("code_challenge_method=S256").contains("dm.write").contains("offline.access");
        String raw = url.substring(url.indexOf("state=") + 6);
        return URLDecoder.decode(raw.substring(0, raw.indexOf('&')), StandardCharsets.UTF_8);
    }

    void connect(Tenant t) throws Exception {
        String loc = mvc.perform(get("/api/v1/chat/x/callback").header("Host", "platform.test").param("code", "good").param("state", state(t))).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(loc).isEqualTo("https://" + t.host() + "/dashboard/channels/connect?connected=X");
    }

    String dm(String shop, String sender, String eventId, String text) {
        return "{\"data\":{\"event_uuid\":\"u\",\"created_at\":\"2026-11-01T10:00:00Z\",\"filter\":{\"user_id\":\"%s\"},\"event_type\":\"dm.received\",\"payload\":{\"direct_message_events\":[{\"type\":\"message_create\",\"id\":\"%s\",\"created_timestamp\":\"1\",\"message_create\":{\"target\":{\"recipient_id\":\"%s\"},\"sender_id\":\"%s\",\"message_data\":{\"text\":\"%s\"}}}],\"users\":{\"%s\":{\"data\":{\"username\":\"sara\",\"name\":\"Sara\"}}}}}}"
                .formatted(shop, eventId, shop, sender, text, sender);
    }

    ResultActions webhook(String raw, String signature) throws Exception {
        var b = post("/api/v1/chat/x/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw);
        if (signature != null) b.header("X-Twitter-Webhooks-Signature-OAuth2", signature);
        return mvc.perform(b);
    }

    List<JsonNode> sentTo(String user) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (var c : FakeHttpGateway.calls("POST", "/2/dm_conversations/with/" + user + "/messages")) out.add(JSON.readTree(c.body()));
        return out;
    }

    @Test
    void xChecksTheAddressWithItsCrcTokenAndOnlyOurSignatureIsTrusted() throws Exception {
        String res = body(mvc.perform(get("/api/v1/chat/x/webhook").header("Host", "platform.test").param("crc_token", "challenge123")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(res, "$.response_token")).isEqualTo("sha256=" + Base64.getEncoder().encodeToString(hmac("challenge123")));
        String raw = dm(shopX, "555", "e-" + uniq(), "hi");
        webhook(raw, null).andExpect(status().isForbidden());
        webhook(raw, "sha256=AAAA").andExpect(status().isForbidden());
        webhook(raw, sign(raw)).andExpect(status().isOk());
        // the platform team registers the address with X once; the id is kept
        assertThat(x.registerWebhook()).isEqualTo("wh-77");
        assertThat(jdbc.sql("SELECT value FROM chat.social_config WHERE platform = 'X' AND key = 'webhook_id'").query(String.class).single()).isEqualTo("wh-77");
        assertThat(FakeHttpGateway.calls("POST", "/2/webhooks").get(0).headers().get("Authorization")).isEqualTo("Bearer xbearer");
    }

    @Test
    void theOwnerLogsInAndTheAssistantAnswersTheirDmsThroughXsDmApi() throws Exception {
        Tenant t = onboard("STORE");
        x.registerWebhook();
        connect(t);
        // the code was exchanged with PKCE and the app's own client login; the account was found and DMs subscribed
        var token = FakeHttpGateway.calls("POST", "/2/oauth2/token").get(0);
        assertThat(token.body()).contains("code_verifier=").contains("grant_type=authorization_code");
        assertThat(token.headers().get("Authorization")).startsWith("Basic ");
        assertThat(FakeHttpGateway.calls("POST", "/2/activity/subscriptions").get(0).body()).contains("dm.received").contains(shopX).contains("wh-77");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/x", null).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.channel.status").value("ACTIVE")).andExpect(jsonPath("$.channel.name").value("@shopx"));
        assertThat(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/channels", null))).contains("\"X\"").doesNotContain("ACCESS-1").doesNotContain("REFRESH-1");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! What are you looking for?"));
        String customer = "7" + (100000000L + (System.nanoTime() % 899999999L));
        String raw = dm(shopX, customer, "ev-" + uniq(), "hello I have a question");
        webhook(raw, sign(raw)).andExpect(status().isOk());
        assertThat(sentTo(customer)).hasSize(1);
        assertThat(sentTo(customer).get(0).path("text").asText()).contains("Welcome");
        assertThat(FakeHttpGateway.calls("POST", "/2/dm_conversations/with/" + customer).get(0).headers().get("Authorization")).isEqualTo("Bearer ACCESS-1");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = CAST(:t AS uuid) AND channel = 'X' AND external_id = :e").param("t", tid(t)).param("e", customer).query(Long.class).single()).isEqualTo(1L);

        // the same event again is answered once; the shop's own message and a DM for an account nobody connected are ignored
        FakeHttpGateway.CALLS.clear();
        webhook(raw, sign(raw)).andExpect(status().isOk());
        String own = dm(shopX, shopX, "ev-" + uniq(), "sent by us");
        webhook(own, sign(own)).andExpect(status().isOk());
        String stranger = dm("8" + uniq().replaceAll("\\D", "1"), customer, "ev-" + uniq(), "hello");
        webhook(stranger, sign(stranger)).andExpect(status().isOk());
        assertThat(FakeHttpGateway.calls("POST", "/2/dm_conversations/with/")).isEmpty();
    }

    @Test
    void aForgedLoginStateIsRefusedAndALoginThatXRefusesDoesNotConnect() throws Exception {
        Tenant t = onboard("STORE");
        String good = state(t);
        mvc.perform(get("/api/v1/chat/x/callback").header("Host", "platform.test").param("code", "good").param("state", good.substring(0, good.length() - 3) + "AAA")).andExpect(status().isBadRequest());
        assertThat(mvc.perform(get("/api/v1/chat/x/callback").header("Host", "platform.test").param("state", state(t))).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location")).endsWith("?error=denied");
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/2/oauth2/token", 400, "{\"error\":\"invalid_request\"}");
        assertThat(mvc.perform(get("/api/v1/chat/x/callback").header("Host", "platform.test").param("code", "bad").param("state", state(t))).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location")).endsWith("?error=failed");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/x", null).andExpect(jsonPath("$.channel").doesNotExist());
    }

    @Test
    void anExpiredLoginIsRenewedBeforeTheReplyIsSent() throws Exception {
        Tenant t = onboard("STORE");
        connect(t);
        jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = 'X' AND external_id = :e").param("e", shopX)
                .param("k", crypto.encrypt("{\"access\":\"OLD-ACCESS\",\"refresh\":\"REFRESH-1\",\"expiresAt\":1}")).update();
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/2/oauth2/token", 200, "{\"access_token\":\"ACCESS-2\",\"refresh_token\":\"REFRESH-2\",\"expires_in\":7200}");
        FakeHttpGateway.when("POST", "/2/dm_conversations/with/", 200, "{\"data\":{\"dm_event_id\":\"e2\"}}");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! How can I help?"));
        String customer = "6" + (100000000L + (System.nanoTime() % 899999999L));
        String raw = dm(shopX, customer, "ev-" + uniq(), "hello");
        webhook(raw, sign(raw)).andExpect(status().isOk());
        assertThat(FakeHttpGateway.calls("POST", "/2/oauth2/token").get(0).body()).contains("grant_type=refresh_token").contains("REFRESH-1");
        assertThat(FakeHttpGateway.calls("POST", "/2/dm_conversations/with/" + customer).get(0).headers().get("Authorization")).isEqualTo("Bearer ACCESS-2");
    }
}

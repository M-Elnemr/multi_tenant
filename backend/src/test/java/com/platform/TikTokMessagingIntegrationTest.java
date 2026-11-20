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
import com.platform.chat.SocialBridge;
import com.platform.chat.TikTokService;
import com.platform.integrations.HttpGateway;
import com.platform.chat.TokenCrypto;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TikTok Business Messaging, built from TikTok's official pages: the authorization code and tokens, the signed webhook (Tiktok-Signature), replies with /business/message/send/, TikTok's own limits, and the
 * states of a connection (authorized is not the same as able to message). TikTok is faked here with the endpoints and fields its documentation lists: this is NOT a test against a real TikTok account.
 */
class TikTokMessagingIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String API = "https://business-api.tiktok.com/open_api/v1.3";
    @Autowired JdbcClient jdbc;
    @Autowired TikTokService tiktok;
    @Autowired SocialBridge bridge;
    @Autowired TokenCrypto crypto;

    @BeforeEach
    void clean() {
        FakeAiProvider.reset();
        FakeHttpGateway.reset();
        ok();
    }

    String openId = "oid-" + System.nanoTime();

    void ok() { ok(""); }

    /** TikTok's healthy answers, except the one for {@code skip} (the first matching answer wins, so a different answer for it is registered by the test after this). */
    void ok(String skip) {
        if (!"/tt_user/oauth2/token/".equals(skip)) FakeHttpGateway.when("POST", "/tt_user/oauth2/token/", 200, "{\"code\":0,\"message\":\"OK\",\"data\":{\"access_token\":\"AT-1\",\"expires_in\":86400,\"open_id\":\"" + openId + "\",\"refresh_token\":\"RT-1\",\"refresh_token_expires_in\":31536000,\"scope\":\"message.list.manage,message.list.send,message.list.read,user.account.type\",\"token_type\":\"Bearer\"}}");
        if (!"/business/message/conversation/list/".equals(skip)) FakeHttpGateway.when("GET", "/business/message/conversation/list/", 200, "{\"code\":0,\"message\":\"OK\",\"data\":{\"conversations\":[],\"has_more\":false,\"cursor\":0}}");
        if (!"/business/message/send/".equals(skip)) FakeHttpGateway.when("POST", "/business/message/send/", 200, "{\"code\":0,\"message\":\"OK\",\"data\":{\"message\":{\"message_id\":\"mid-out\"}}}");
        FakeHttpGateway.when("POST", "/business/webhook/update/", 200, "{\"code\":0,\"message\":\"OK\",\"data\":{\"event_type\":\"DIRECT_MESSAGE\"}}");
    }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tid(Tenant t) { return jdbc.sql("SELECT tenant_id FROM core.tenant_domains WHERE host = :h").param("h", t.host()).query(UUID.class).single(); }

    String state(Tenant t) throws Exception {
        String url = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/tiktok/start", null).andExpect(status().isOk())), "$.url");
        assertThat(url).startsWith("https://www.tiktok.com/v2/auth/authorize?client_key=ttappid").contains("&state=").endsWith("&disable_auto_auth=1");
        String raw = url.substring(url.indexOf("&state=") + 7);
        return URLDecoder.decode(raw.substring(0, raw.indexOf('&')), StandardCharsets.UTF_8);
    }

    String callback(Tenant t, String code) throws Exception {
        var req = get("/api/v1/chat/tiktok/callback").header("Host", "platform.test").param("state", state(t));
        if (code != null) req.param("code", code);
        return mvc.perform(req).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
    }

    static String sig(String raw, long t, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder h = new StringBuilder();
        for (byte b : mac.doFinal((t + "." + raw).getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return "t=" + t + ",s=" + h;
    }

    ResultActions webhook(String raw, String signature) throws Exception {
        var b = post("/api/v1/chat/tiktok/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw);
        if (signature != null) b.header("Tiktok-Signature", signature);
        return mvc.perform(b);
    }

    ResultActions signed(String raw) throws Exception { return webhook(raw, sig(raw, Instant.now().getEpochSecond(), "ttsecret")); }

    /** TikTok's own envelope: the event, the app, the business (user_openid), and a content that is itself a JSON string. */
    String received(String business, String conversation, String messageId, String text) throws Exception {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("from", "customer_x"); c.put("to", "shop"); c.put("unique_identifier", "u1"); c.put("from_user", Map.of("id", "u1", "role", "personal_account")); c.put("to_user", Map.of("id", business, "role", "business_account"));
        c.put("conversation_id", conversation); c.put("message_id", messageId); c.put("timestamp", System.currentTimeMillis()); c.put("text", Map.of("body", text)); c.put("type", "text"); c.put("scene_type", 0); c.put("is_follower", false); c.put("message_tag", Map.of("source", "APP"));
        String content = JSON.writeValueAsString(c);
        return JSON.writeValueAsString(Map.of("client_key", "ttappid", "event", "im_receive_msg", "create_time", Instant.now().getEpochSecond(), "user_openid", business, "content", content));
    }

    List<JsonNode> sends() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (var c : FakeHttpGateway.calls("POST", "/business/message/send/")) out.add(JSON.readTree(c.body()));
        return out;
    }

    void connect(Tenant t) throws Exception {
        assertThat(callback(t, "authcode")).isEqualTo("https://" + t.host() + "/dashboard/channels/connect?connected=TIKTOK");
    }

    @Test
    void theOwnerAuthorizesAndTheAccountIsTestedBeforeItCountsAsConnected() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.channel").doesNotExist());
        connect(t);
        // the auth code was exchanged exactly as TikTok documents, with the app's id, secret and redirect address
        var token = JSON.readTree(FakeHttpGateway.calls("POST", "/tt_user/oauth2/token/").get(0).body());
        assertThat(token.path("grant_type").asText()).isEqualTo("authorization_code");
        assertThat(token.path("auth_code").asText()).isEqualTo("authcode");
        assertThat(token.path("client_id").asText()).isEqualTo("ttappid");
        assertThat(token.path("redirect_uri").asText()).isEqualTo("https://platform.test/api/v1/chat/tiktok/callback");
        // the account was asked for its conversations (the documented way to know messaging works) with the access token in the header
        var probe = FakeHttpGateway.calls("GET", "/business/message/conversation/list/").get(0);
        assertThat(probe.url()).contains("business_id=" + openId).contains("conversation_type=SINGLE");
        assertThat(probe.headers().get("Access-Token")).isEqualTo("AT-1");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel.status").value("ACTIVE")).andExpect(jsonPath("$.channel.connectionState").value("CONNECTED"));
        assertThat(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/channels", null))).contains("TIKTOK").doesNotContain("AT-1").doesNotContain("RT-1");
        assertThat(jdbc.sql("SELECT token_enc FROM chat.channels WHERE platform = 'TIKTOK' AND external_id = :e").param("e", openId).query(String.class).single()).doesNotContain("AT-1");   // encrypted at rest
    }

    @Test
    void authorizedButNotAbleToMessageIsShownAsWhatIsMissingAndNeverAsConnected() throws Exception {
        Tenant t = onboard("STORE");
        // TikTok says the account has no permission (not a Verified Business Account / the app is not approved yet)
        FakeHttpGateway.reset(); ok();
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/tt_user/oauth2/token/", 200, "{\"code\":0,\"data\":{\"access_token\":\"AT-1\",\"expires_in\":86400,\"open_id\":\"" + openId + "\",\"refresh_token\":\"RT-1\",\"refresh_token_expires_in\":31536000,\"scope\":\"message.list.send,message.list.read\"}}");
        FakeHttpGateway.when("GET", "/business/message/conversation/list/", 200, "{\"code\":40001,\"message\":\"No permission to perform the related operation.\"}");
        assertThat(callback(t, "authcode")).endsWith("?error=approval");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel.status").value("PAUSED")).andExpect(jsonPath("$.channel.connectionState").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.channel.statusNote").value(org.hamcrest.Matchers.containsString("Verified Business Account")));
        // a customer writing now gets nothing: the account is not connected
        String raw = received(openId, "conv-1", "m-1", "hello");
        signed(raw).andExpect(status().isOk());
        assertThat(sends()).isEmpty();
        // the shop fixes it (or TikTok approves) and checks again
        FakeHttpGateway.reset(); ok();
        FakeHttpGateway.when("POST", "/tt_user/oauth2/refresh_token/", 200, "{\"code\":0,\"data\":{\"access_token\":\"AT-2\",\"expires_in\":86400,\"open_id\":\"" + openId + "\",\"refresh_token\":\"RT-2\",\"refresh_token_expires_in\":31536000}}");
        jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = 'TIKTOK' AND external_id = :e").param("e", openId)
                .param("k", crypto.encrypt("{\"access\":\"AT-1\",\"refresh\":\"RT-1\",\"accessExp\":" + (Instant.now().getEpochSecond() + 3600) + ",\"refreshExp\":" + (Instant.now().getEpochSecond() + 99999) + "}")).update();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/tiktok/recheck", null).andExpect(status().isOk()).andExpect(jsonPath("$.channel.connectionState").value("CONNECTED")).andExpect(jsonPath("$.channel.status").value("ACTIVE"));
        // a region TikTok does not serve is reported as a restriction, with the reason
        Tenant u = onboard("STORE");
        FakeHttpGateway.reset();
        FakeHttpGateway.when("GET", "/business/message/conversation/list/", 200, "{\"code\":40007,\"message\":\"The operation/object does not exist.\"}");
        FakeHttpGateway.when("POST", "/tt_user/oauth2/token/", 200, "{\"code\":0,\"data\":{\"access_token\":\"AT-9\",\"expires_in\":86400,\"open_id\":\"oid-eu-" + System.nanoTime() + "\",\"refresh_token\":\"RT-9\",\"refresh_token_expires_in\":31536000,\"scope\":\"message.list.send,message.list.read\"}}");
        assertThat(callback(u, "authcode")).endsWith("?error=restricted");
        onHost(u.host(), u.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel.connectionState").value("RESTRICTED")).andExpect(jsonPath("$.channel.statusNote").value(org.hamcrest.Matchers.containsString("EEA")));
    }

    @Test
    void aForgedOrRefusedLoginDoesNotConnectAnything() throws Exception {
        Tenant t = onboard("STORE");
        String good = state(t);
        mvc.perform(get("/api/v1/chat/tiktok/callback").header("Host", "platform.test").param("code", "x").param("state", good.substring(0, good.length() - 3) + "AAA")).andExpect(status().isBadRequest());
        assertThat(callback(t, null)).endsWith("?error=denied");   // refused, or TikTok skipped the permission page (the auth code is missing)
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/tt_user/oauth2/token/", 200, "{\"code\":40002,\"message\":\"auth_code expired\"}");
        assertThat(callback(t, "old")).endsWith("?error=failed");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel").doesNotExist());
    }

    @Test
    void onlyTikToksSignedAndFreshDeliveriesAreTrustedAndEachMessageIsAnsweredOnce() throws Exception {
        Tenant t = onboard("STORE");
        connect(t);
        String raw = received(openId, "conv-" + uniq(), "m-" + uniq(), "hello");
        webhook(raw, null).andExpect(status().isForbidden());
        webhook(raw, "t=" + Instant.now().getEpochSecond() + ",s=" + "0".repeat(64)).andExpect(status().isForbidden());
        webhook(raw, sig(raw, Instant.now().getEpochSecond() - 3600, "ttsecret")).andExpect(status().isForbidden());   // a correct signature on an old timestamp is a replay
        webhook(raw, sig(raw, Instant.now().getEpochSecond(), "another-secret")).andExpect(status().isForbidden());
        assertThat(sends()).isEmpty();

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! What are you looking for?"));
        String conv = "conv-" + uniq();
        String msg = received(openId, conv, "m-" + uniq(), "hello I need help");
        signed(msg).andExpect(status().isOk());
        assertThat(sends()).hasSize(1);
        var out = sends().get(0);
        assertThat(out.path("business_id").asText()).isEqualTo(openId);
        assertThat(out.path("recipient_type").asText()).isEqualTo("CONVERSATION");
        assertThat(out.path("recipient").asText()).isEqualTo(conv);
        assertThat(out.path("message_type").asText()).isEqualTo("TEXT");
        assertThat(out.path("text").path("body").asText()).contains("Welcome");
        assertThat(FakeHttpGateway.calls("POST", "/business/message/send/").get(0).headers().get("Access-Token")).isEqualTo("AT-1");
        // TikTok delivers the same event again: one answer only; and our own sent messages (im_send_msg) come back and are not customers
        FakeHttpGateway.CALLS.clear();
        signed(msg).andExpect(status().isOk());
        String own = JSON.writeValueAsString(Map.of("client_key", "ttappid", "event", "im_send_msg", "create_time", Instant.now().getEpochSecond(), "user_openid", openId, "content", "{\"conversation_id\":\"" + conv + "\",\"message_id\":\"mid-out\",\"type\":\"text\",\"text\":{\"body\":\"Welcome\"},\"from_user\":{\"role\":\"business_account\"}}"));
        signed(own).andExpect(status().isOk());
        String eu = JSON.writeValueAsString(Map.of("client_key", "ttappid", "event", "im_receive_msg_eu", "create_time", Instant.now().getEpochSecond(), "user_openid", openId, "content", "{\"to\":\"shop\",\"timestamp\":1}"));
        signed(eu).andExpect(status().isOk());
        assertThat(sends()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND channel = 'TIKTOK' AND external_id = :e").param("t", tid(t)).param("e", conv).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aMessageForAnotherShopsAccountOrAnUnknownOneIsNeverRoutedToTheWrongShop() throws Exception {
        Tenant a = onboard("STORE");
        connect(a);
        String aOpen = openId;
        openId = "oid-b-" + System.nanoTime();
        FakeHttpGateway.reset();
        ok();
        Tenant b = onboard("STORE");
        connect(b);
        String bOpen = openId;
        FakeHttpGateway.CALLS.clear();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello from shop B"));
        String conv = "conv-" + uniq();
        signed(received(bOpen, conv, "m-" + uniq(), "hi")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT tenant_id FROM chat.conversations WHERE channel = 'TIKTOK' AND external_id = :e").param("e", conv).query(UUID.class).single()).isEqualTo(tid(b));
        assertThat(FakeHttpGateway.calls("POST", "/business/message/send/")).hasSize(1);
        assertThat(JSON.readTree(FakeHttpGateway.calls("POST", "/business/message/send/").get(0).body()).path("business_id").asText()).isEqualTo(bOpen);
        // an account nobody connected: nothing happens
        FakeHttpGateway.CALLS.clear();
        signed(received("oid-unknown", "conv-z", "m-z", "hi")).andExpect(status().isOk());
        assertThat(sends()).isEmpty();
        assertThat(aOpen).isNotEqualTo(bOpen);
        onHost(a.host(), a.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel.connectionState").value("CONNECTED"));
    }

    @Test
    void tikToksMessageLimitsAreEnforcedBeforeAnythingIsSent() throws Exception {
        Tenant t = onboard("STORE");
        connect(t);
        String conv = "conv-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello!"));
        signed(received(openId, conv, "m-" + uniq(), "hi")).andExpect(status().isOk());   // the customer started it: 1 message sent
        var row = bridge.channelRow("TIKTOK", openId);
        FakeHttpGateway.CALLS.clear();
        // up to 10 within the first 48 hours (one already went): nine more, then no more until the customer answers
        for (int i = 0; i < 9; i++) tiktok.send(row, conv, "m" + i, null);
        assertThat(sends()).hasSize(9);
        try { tiktok.send(row, conv, "one too many", null); org.junit.jupiter.api.Assertions.fail("the 11th message must be refused"); } catch (HttpGateway.ApiException e) { assertThat(e.getMessage()).contains("limits"); }
        assertThat(sends()).hasSize(9);   // nothing was sent to TikTok for it
        // the customer replies: unlimited for the next 48 hours
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) SELECT id, 'user', 'thanks' FROM chat.conversations WHERE channel = 'TIKTOK' AND external_id = :e").param("e", conv).update();
        for (int i = 0; i < 12; i++) tiktok.send(row, conv, "free" + i, null);
        // 48 hours of silence: only three more until they write again
        jdbc.sql("UPDATE chat.messages SET created_at = now() - interval '3 days' WHERE conversation_id = (SELECT id FROM chat.conversations WHERE channel = 'TIKTOK' AND external_id = :e)").param("e", conv).update();
        jdbc.sql("UPDATE chat.social_sends SET sent_at = now() - interval '3 days' WHERE conversation_ref = :e").param("e", conv).update();
        for (int i = 0; i < 3; i++) tiktok.send(row, conv, "late" + i, null);
        try { tiktok.send(row, conv, "late 4", null); org.junit.jupiter.api.Assertions.fail("only three after 48 hours of silence"); } catch (HttpGateway.ApiException e) { assertThat(e.status).isEqualTo(429); }
        // a conversation the customer never started cannot be written to at all
        try { tiktok.send(row, "conv-never-started", "hi", null); org.junit.jupiter.api.Assertions.fail("never"); } catch (HttpGateway.ApiException e) { assertThat(e.getMessage()).contains("limits"); }
    }

    @Test
    void anExpiredAccessTokenIsRenewedAndARevokedLoginPausesTheAccountInsteadOfClaimingSuccess() throws Exception {
        Tenant t = onboard("STORE");
        connect(t);
        var row = bridge.channelRow("TIKTOK", openId);
        String conv = "conv-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hi!"));
        signed(received(openId, conv, "m-" + uniq(), "hello")).andExpect(status().isOk());
        jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = 'TIKTOK' AND external_id = :e").param("e", openId)
                .param("k", crypto.encrypt("{\"access\":\"OLD\",\"refresh\":\"RT-1\",\"accessExp\":1,\"refreshExp\":" + (Instant.now().getEpochSecond() + 99999) + "}")).update();
        FakeHttpGateway.CALLS.clear();
        FakeHttpGateway.when("POST", "/tt_user/oauth2/refresh_token/", 200, "{\"code\":0,\"data\":{\"access_token\":\"AT-NEW\",\"expires_in\":86400,\"open_id\":\"" + openId + "\",\"refresh_token\":\"RT-NEW\",\"refresh_token_expires_in\":31536000}}");
        tiktok.send(bridge.channelRow("TIKTOK", openId), conv, "after renewal", null);
        assertThat(JSON.readTree(FakeHttpGateway.calls("POST", "/tt_user/oauth2/refresh_token/").get(0).body()).path("refresh_token").asText()).isEqualTo("RT-1");
        assertThat(FakeHttpGateway.calls("POST", "/business/message/send/").get(0).headers().get("Access-Token")).isEqualTo("AT-NEW");
        // the owner revoked the app: TikTok refuses the renewal, the account is paused with the reason and nothing is claimed as sent
        jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = 'TIKTOK' AND external_id = :e").param("e", openId)
                .param("k", crypto.encrypt("{\"access\":\"OLD\",\"refresh\":\"RT-1\",\"accessExp\":1,\"refreshExp\":" + (Instant.now().getEpochSecond() + 99999) + "}")).update();
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/tt_user/oauth2/refresh_token/", 200, "{\"code\":40105,\"message\":\"Invalid or incorrect access token\"}");
        try { tiktok.send(bridge.channelRow("TIKTOK", openId), conv, "never arrives", null); org.junit.jupiter.api.Assertions.fail("must fail"); } catch (HttpGateway.ApiException e) { assertThat(e.getMessage()).contains("connect TikTok again"); }
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/tiktok", null).andExpect(jsonPath("$.channel.connectionState").value("REAUTH_REQUIRED")).andExpect(jsonPath("$.channel.status").value("PAUSED"));
    }

    @Test
    void whenTikTokRefusesAReplyTheShopSeesItAndTheCustomerIsNotToldItWasSent() throws Exception {
        Tenant t = onboard("STORE");
        connect(t);
        FakeHttpGateway.reset();
        ok("/business/message/send/");
        FakeHttpGateway.when("POST", "/business/message/send/", 200, "{\"code\":40064,\"message\":\"The message is blocked due to limitation of direct message rules.\"}");
        FakeHttpGateway.CALLS.clear();
        String conv = "conv-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello! How can I help?"));
        signed(received(openId, conv, "m-" + uniq(), "hi")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM chat.social_sends WHERE conversation_ref = :e").param("e", conv).query(Long.class).single()).as("a refused message is not counted as sent").isZero();
        assertThat(jdbc.sql("SELECT meta FROM chat.conversation_events WHERE kind = 'DELIVERY_FAILED' AND conversation_id = (SELECT id FROM chat.conversations WHERE external_id = :e)").param("e", conv).query(String.class).single()).contains("40064");
    }

    @Test
    void thePlatformTeamSubscribesTheAppToDirectMessagesOnce() throws Exception {
        tiktok.registerWebhook();
        var sent = JSON.readTree(FakeHttpGateway.calls("POST", "/business/webhook/update/").get(0).body());
        assertThat(sent.path("event_type").asText()).isEqualTo("DIRECT_MESSAGE");
        assertThat(sent.path("callback_url").asText()).isEqualTo("https://platform.test/api/v1/chat/tiktok/webhook");
        assertThat(sent.path("app_id").asText()).isEqualTo("ttappid");
    }
}

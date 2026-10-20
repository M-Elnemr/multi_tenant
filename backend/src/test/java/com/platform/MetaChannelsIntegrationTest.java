package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Facebook / Instagram / WhatsApp: connecting a shop, the signed webhook, the AI answering through the channel. Meta and the model are faked. */
class MetaChannelsIntegrationTest extends IntegrationTestBase {
    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    static String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("testsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder h = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return h.toString();
    }

    ResultActions webhook(String raw, String signature) throws Exception {
        var b = post("/api/v1/chat/meta/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw);
        if (signature != null) b.header("X-Hub-Signature-256", signature);
        return mvc.perform(b);
    }

    static String pageMessage(String pageId, String sender, String mid, String text) {
        return "{\"object\":\"page\",\"entry\":[{\"id\":\"%s\",\"messaging\":[{\"sender\":{\"id\":\"%s\"},\"recipient\":{\"id\":\"%s\"},\"message\":{\"mid\":\"%s\",\"text\":\"%s\"}}]}]}".formatted(pageId, sender, pageId, mid, text);
    }

    static String pageId() { return String.valueOf(100000000000L + (System.nanoTime() % 899999999999L)); }

    String connect(Tenant t, String platform, String id) throws Exception {
        return body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels",
                "{\"platform\":\"%s\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(platform, id)).andExpect(status().isCreated()));
    }

    @Test
    void webhookIsVerifiedAndOnlyCorrectlySignedDeliveriesAreAccepted() throws Exception {
        mvc.perform(get("/api/v1/chat/meta/webhook").header("Host", "platform.test").param("hub.mode", "subscribe").param("hub.verify_token", "verifytest").param("hub.challenge", "12345"))
                .andExpect(status().isOk()).andExpect(content().string("12345"));
        mvc.perform(get("/api/v1/chat/meta/webhook").header("Host", "platform.test").param("hub.mode", "subscribe").param("hub.verify_token", "wrong").param("hub.challenge", "12345")).andExpect(status().isForbidden());
        String raw = pageMessage("999", "1", "m1", "hi");
        webhook(raw, null).andExpect(status().isForbidden());
        webhook(raw, "sha256=" + "0".repeat(64)).andExpect(status().isForbidden());
        webhook(raw, sign(raw)).andExpect(status().isOk());
    }

    @Test
    void shopConnectsAPageAndTheAssistantAnswersMessengerCustomers() throws Exception {
        Tenant t = onboard("STORE");
        String page = pageId();
        String res = connect(t, "FACEBOOK", page);
        assertThat(res).doesNotContain("EAAG").doesNotContain("token");   // the token is never returned
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/channels", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].platform").value("FACEBOOK"));
        assertThat(FakeMetaGraph.SENT.get(0).path()).isEqualTo(page + "/subscribed_apps");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! What are you looking for?"));
        String raw = pageMessage(page, "user-1", "mid-" + uniq(), "hello");
        webhook(raw, sign(raw)).andExpect(status().isOk());
        var sent = FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1);
        assertThat(sent.path()).isEqualTo("me/messages");
        assertThat(sent.token()).isEqualTo("EAAG-secret-token-0123456789");
        assertThat(sent.body().toString()).contains("user-1").contains("Welcome!");

        // the same delivery again, an echo of the page's own message, and an unknown page are all ignored
        int count = FakeMetaGraph.SENT.size();
        webhook(raw, sign(raw)).andExpect(status().isOk());
        String echo = "{\"object\":\"page\",\"entry\":[{\"id\":\"%s\",\"messaging\":[{\"sender\":{\"id\":\"%s\"},\"message\":{\"mid\":\"mid-e%s\",\"is_echo\":true,\"text\":\"x\"}}]}]}".formatted(page, page, uniq());
        webhook(echo, sign(echo)).andExpect(status().isOk());
        String other = pageMessage("424242424242", "u", "mid-" + uniq(), "hi");
        webhook(other, sign(other)).andExpect(status().isOk());
        assertThat(FakeMetaGraph.SENT.size()).isEqualTo(count);
    }

    @Test
    void whatsappMessageGetsAnAnswerAndAnOrderMadeThereCarriesTheTrackingLink() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String variant = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String pnid = pageId();
        connect(t, "WHATSAPP", pnid);
        FakeMetaGraph.reset();

        java.util.function.Function<String, String> wa = text -> "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"%s\"},\"messages\":[{\"from\":\"201011112222\",\"id\":\"wamid.%s\",\"type\":\"text\",\"text\":{\"body\":\"%s\"}}]}}]}]}".formatted(pnid, uniq(), text);
        Map<String, Object> prep = Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "customerName", "Hana Said", "phone", "01011112222", "governorateCode", "CAI",
                "addressLine1", "7 Nile St, apt 3", "area", "Maadi", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prep));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Total 150, confirm?"));
        String m1 = wa.apply("one mug");
        webhook(m1, sign(m1)).andExpect(status().isOk());
        assertThat(FakeMetaGraph.SENT.get(0).path()).isEqualTo(pnid + "/messages");
        assertThat(FakeMetaGraph.SENT.get(0).body().toString()).contains("messaging_product=whatsapp").contains("201011112222");

        String m2 = wa.apply("yes");
        webhook(m2, sign(m2)).andExpect(status().isOk());
        String last = FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body().toString();
        assertThat(last).contains("confirmed").contains("https://" + t.host() + "/track?n=");
        String list = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/orders", null));
        assertThat((Integer) JsonPath.read(list, "$.meta.total")).isEqualTo(1);
        assertThat(JsonPath.<String>read(list, "$.data[0].source")).isEqualTo("AI_CHAT");
    }

    @Test
    void anAccountCannotBeConnectedToTwoShopsAndBadTokensAreRefused() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        String page = pageId();
        connect(a, "FACEBOOK", page);
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(page)).andExpect(status().isConflict());
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"BAD-token-0123456789012345\"}".formatted(pageId())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("META_REJECTED"));
        onHost(b.host(), b.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"TIKTOK\",\"externalId\":\"12345678\",\"accessToken\":\"EAAG-secret-token-0123456789\"}").andExpect(status().isBadRequest());
        // b cannot see or remove a's channel
        onHost(b.host(), b.access(), "GET", "/api/v1/store/ai/channels", null).andExpect(jsonPath("$.length()").value(0));
        String id = JsonPath.read(body(onHost(a.host(), a.access(), "GET", "/api/v1/store/ai/channels", null)), "$[0].id");
        onHost(b.host(), b.access(), "DELETE", "/api/v1/store/ai/channels/" + id, null).andExpect(status().isNotFound());
        onHost(a.host(), a.access(), "DELETE", "/api/v1/store/ai/channels/" + id, null).andExpect(status().isNoContent());
    }
}

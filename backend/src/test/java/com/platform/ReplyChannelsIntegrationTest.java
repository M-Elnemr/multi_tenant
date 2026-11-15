package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.MessageReplies;
import java.nio.charset.StandardCharsets;
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
 * A customer's native «reply» on WhatsApp (Cloud and own number), Messenger and Instagram reaches the assistant as the same «message being replied to» as a website reply. The
 * platforms and the model are faked: this proves the mapping and what the assistant is given, not what the real platforms send in every case.
 */
class ReplyChannelsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired MessageReplies replies;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); FakeEvolutionApi.reset(); }

    UUID tenant(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    static String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("testsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder h = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return h.toString();
    }

    ResultActions meta(String raw) throws Exception {
        return mvc.perform(post("/api/v1/chat/meta/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw).header("X-Hub-Signature-256", sign(raw)));
    }

    void connect(Tenant t, String platform, String id) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"%s\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(platform, id)).andExpect(status().isCreated());
    }

    static String wa(String pnid, String from, String id, String text, String contextId) {
        String ctx = contextId == null ? "" : ",\"context\":{\"from\":\"15550001\",\"id\":\"%s\"}".formatted(contextId);
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"%s\"},\"messages\":[{\"from\":\"%s\",\"id\":\"%s\",\"type\":\"text\",\"text\":{\"body\":\"%s\"}%s}]}}]}]}".formatted(pnid, from, id, text, ctx);
    }

    static String page(String object, String pageId, String sender, String mid, String text, String replyTo) {
        return "{\"object\":\"%s\",\"entry\":[{\"id\":\"%s\",\"messaging\":[{\"sender\":{\"id\":\"%s\"},\"recipient\":{\"id\":\"%s\"},\"message\":{\"mid\":\"%s\",\"text\":\"%s\"%s}}]}]}".formatted(object, pageId, sender, pageId, mid, text, replyTo);
    }

    static String pageId() { return String.valueOf(100000000000L + (System.nanoTime() % 899999999999L)); }

    @Test
    void aWhatsAppCloudReplyFindsTheBubbleWeSentThroughTheIdTheSendReturned() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String pnid = pageId();
        connect(t, "WHATSAPP", pnid);
        FakeMetaGraph.reset();
        String from = "2010" + (10_000_000 + System.nanoTime() % 80_000_000);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("عايز شنطة", turns -> Turn.model("أهلا بيك في المحل يا فندم")));
        String inB = "wamid.IN-B" + uniq();
        String m1 = wa(pnid, from, "wamid.IN-A" + uniq(), "عايز شنطة", null);
        meta(m1).andExpect(status().isOk());
        String sentMid = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text LIKE '%أهلا بيك في المحل%'").param("t", tenant).query(String.class).single();
        assertThat(sentMid).startsWith("wamid.TEST");   // the id the platform returned for the bubble we sent
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'WHATSAPP'").param("t", tenant).query(UUID.class).single();
        for (int i = 0; i < 30; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', :x)").param("c", conv).param("x", "later " + i).update();

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        String m2 = wa(pnid, from, inB, "بتيجي بلون تاني؟", sentMid);
        meta(m2).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("MESSAGE BEING REPLIED TO").contains("ORIGINAL MESSAGE SENDER: you, the shop's assistant").contains("ORIGINAL MESSAGE CONTENT: «أهلا بيك في المحل يا فندم»");
        assertThat(jdbc.sql("SELECT reply_to_mid FROM chat.messages WHERE conversation_id = :c AND content = 'بتيجي بلون تاني؟'").param("c", conv).query(String.class).single()).isEqualTo(sentMid);
        // a customer line's own platform id is kept, so a reply to it later is found as well
        assertThat(replies.idForMid(tenant, conv, inB)).isNotNull();
        // a WhatsApp message with no context sends nothing to resolve: no «replying to» block is invented
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        meta(wa(pnid, from, "wamid.IN-C" + uniq(), "وبكام؟", null)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).doesNotContain("MESSAGE BEING REPLIED TO");
    }

    @Test
    void messengerAndInstagramRepliesAreMappedAndAnInstagramStoryReplyIsNeverGivenInventedContent() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String pid = pageId();
        connect(t, "FACEBOOK", pid);
        FakeMetaGraph.reset();
        String user = "psid" + System.nanoTime();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("عندكم تيشيرت", turns -> Turn.model("أهلا بيك نورتنا")));
        meta(page("page", pid, user, "mid.IN-1" + uniq(), "عندكم تيشيرت", "")).andExpect(status().isOk());
        String sentMid = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text LIKE '%أهلا بيك نورتنا%'").param("t", tenant).query(String.class).single();
        assertThat(sentMid).startsWith("mid.TEST");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        meta(page("page", pid, user, "mid.IN-2" + uniq(), "وبكام ده", ",\"reply_to\":{\"mid\":\"%s\"}".formatted(sentMid))).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: «أهلا بيك نورتنا»");

        // Instagram: a reply to a story carries no text of the story: the assistant is told it is a story and nothing else
        String ig = pageId();
        connect(t, "INSTAGRAM", ig);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        meta(page("instagram", ig, "igsid" + System.nanoTime(), "mid.IG-1" + uniq(), "ده بكام", ",\"reply_to\":{\"story\":{\"url\":\"https://cdn/x\",\"id\":\"s1\"}}")).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: [a story the shop posted]").doesNotContain("https://cdn/x");
    }

    @Test
    void anOwnNumberWhatsAppReplyCarriesItsQuoteAndAnOurOwnMessageIsFoundById() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(status().isOk());
        String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings es JOIN core.tenants c ON c.id = es.tenant_id WHERE c.slug = :s").param("s", t.slug()).query(String.class).single();
        String secret = "evolution-test-secret-0123456789";
        String num = "201055" + (100000 + System.nanoTime() % 800000);
        java.util.function.BiFunction<String, String, ResultActions> hook = (id, inner) -> {
            try { return mvc.perform(post("/api/v1/chat/evolution/webhook/" + secret).header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"%s\"},\"message\":%s}}".formatted(inst, num, id, inner))); }
            catch (Exception e) { throw new RuntimeException(e); }
        };
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("عايز جاكيت", turns -> Turn.model("أهلا بيك نورتنا يا فندم")));
        hook.apply("E-IN-1" + uniq(), "{\"conversation\":\"عايز جاكيت\"}").andExpect(status().isOk());
        String outId = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text LIKE '%نورتنا يا فندم%'").param("t", tenant).query(String.class).single();
        assertThat(outId).startsWith("OUT");

        // she quotes OUR message: found by its id (the stored text wins over whatever the quote says)
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        hook.apply("E-IN-2" + uniq(), "{\"extendedTextMessage\":{\"text\":\"ده بيتغسل؟\",\"contextInfo\":{\"stanzaId\":\"%s\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"نص مختلف\"}}}}".formatted(outId)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: «أهلا بيك نورتنا يا فندم»").doesNotContain("نص مختلف");

        // she quotes a message we never stored (sent from the phone itself): the platform's own quote is used, marked as such, and not as the assistant's
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        hook.apply("E-IN-3" + uniq(), "{\"extendedTextMessage\":{\"text\":\"وده؟\",\"contextInfo\":{\"stanzaId\":\"PHONE-ONLY\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"المقاسات من S لـ XL\"}}}}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE SENDER: the shop").contains("ORIGINAL MESSAGE CONTENT: «المقاسات من S لـ XL»").contains("no longer stored");

        // she quotes her OWN earlier message
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        hook.apply("E-IN-4" + uniq(), "{\"extendedTextMessage\":{\"text\":\"قصدي ده\",\"contextInfo\":{\"stanzaId\":\"SELF\",\"participant\":\"%s@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"عايز مقاس L\"}}}}".formatted(num)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE SENDER: the customer (their own earlier message)").contains("«عايز مقاس L»");
    }
}

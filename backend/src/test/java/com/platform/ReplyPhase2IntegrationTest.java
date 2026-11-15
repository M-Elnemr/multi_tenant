package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import com.platform.chat.MessageReplies;
import com.platform.chat.ReplyRef;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Round 3: burst replies keep each line's reference, Facebook/Instagram ids, WhatsApp Cloud quoting, the page around an old message, the earlier-messages digest, old quoted prices. Scripted assistant, faked platforms. */
class ReplyPhase2IntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired MessageReplies replies;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); }

    UUID tenant(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    static String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("testsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder h = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return h.toString();
    }

    ResultActions meta(String raw) throws Exception { return mvc.perform(post("/api/v1/chat/meta/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw).header("X-Hub-Signature-256", sign(raw))); }

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

    String num() { return "2010" + (10_000_000 + System.nanoTime() % 80_000_000); }

    long seed(UUID conv, String role, String text) { return jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x) RETURNING id").param("c", conv).param("r", role).param("x", text).query(Long.class).single(); }

    UUID start(UUID tenant, String ext) { return chat.reply(tenant, "WHATSAPP", ext, null, "أهلا", null).conversationId(); }

    ChatService.ReplyInput lines(List<ReplyRef> refs, String... mids) { return new ChatService.ReplyInput(refs, List.of(mids)); }

    // ---- burst: each line keeps its own reference ----------------------------------------------------------------------------

    @Test
    void eachLineOfABurstKeepsItsOwnReferenceInOrderAndAnUnquotedLineIsNotGivenOne() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        long a = seed(conv, "assistant", "الجاكيت الأسود متاح بمقاسات كتير");
        long b = seed(conv, "assistant", "الشنطة البني جلد طبيعي");

        // two different originals, three lines, the middle one quotes nothing
        chat.reply(tenant, "WHATSAPP", ext, null, "بكام ده\nوفيه أزرق؟\nوالتاني", null, lines(List.of(ReplyRef.ofId(a).withLine("بكام ده"), ReplyRef.ofId(b).withLine("والتاني")), "m1", "m2", "m3"));
        String system = FakeAiProvider.lastSystem;
        assertThat(system).contains("(1 of 2), quoted by the customer line «بكام ده»").contains("(2 of 2), quoted by the customer line «والتاني»").contains("do not apply a quote to a line that has none");
        assertThat(system.indexOf("الجاكيت الأسود")).isLessThan(system.indexOf("الشنطة البني"));
        var row = jdbc.sql("SELECT reply_to_id, reply_refs::text AS refs FROM chat.messages WHERE conversation_id = :c AND content = :x").param("c", conv).param("x", "بكام ده\nوفيه أزرق؟\nوالتاني").query().singleRow();
        assertThat(((Number) row.get("reply_to_id")).longValue()).as("the primary columns keep the first").isEqualTo(a);
        var refs = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(String.valueOf(row.get("refs")));
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).path("line").asText()).isEqualTo("بكام ده");
        assertThat(refs.get(0).path("id").asLong()).isEqualTo(a);
        assertThat(refs.get(1).path("line").asText()).isEqualTo("والتاني");
        assertThat(refs.get(1).path("id").asLong()).isEqualTo(b);
        long msg = idOfUser(conv, "بكام ده\nوفيه أزرق؟\nوالتاني");
        var again = replies.quotedAll(tenant, conv, msg);   // what a queue or a retry reads back
        assertThat(again).hasSize(2);
        assertThat(again.get(0).line()).isEqualTo("بكام ده");
        assertThat(again.get(0).quoted().text()).contains("الجاكيت الأسود");
        assertThat(again.get(1).quoted().text()).contains("الشنطة البني");

        // the same original quoted by two lines: both lines are named, the original is not mixed up with another
        chat.reply(tenant, "WHATSAPP", ext, null, "بكام\nوبالأزرق؟", null, lines(List.of(ReplyRef.ofId(a).withLine("بكام"), ReplyRef.ofId(a).withLine("وبالأزرق؟")), "m4", "m5"));
        assertThat(FakeAiProvider.lastSystem).contains("quoted by the customer line «بكام»").contains("quoted by the customer line «وبالأزرق؟»").doesNotContain("الشنطة البني").doesNotContain("do not apply a quote");

        // no reference at all: no block
        chat.reply(tenant, "WHATSAPP", ext, null, "شكرا\nمع السلامة", null, lines(List.of(), "m6", "m7"));
        assertThat(FakeAiProvider.lastSystem).doesNotContain("MESSAGE BEING REPLIED TO");
    }

    long idOfUser(UUID conv, String content) { return jdbc.sql("SELECT id FROM chat.messages WHERE conversation_id = :c AND role = 'user' AND content = :x ORDER BY id DESC LIMIT 1").param("c", conv).param("x", content).query(Long.class).single(); }

    @Test
    void aBurstReferenceToAnotherShopsMessageIsNeverResolvedOrStored() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        UUID ta = tenant(a), tb = tenant(b);
        String extA = num(), extB = num();
        UUID convA = start(ta, extA), convB = start(tb, extB);
        long secretA = seed(convA, "assistant", "سر المتجر الأول: خصم خاص");
        chat.reply(tb, "WHATSAPP", extB, null, "ده بكام\nوده؟", null, lines(List.of(ReplyRef.ofId(secretA).withLine("ده بكام")), "x1", "x2"));
        assertThat(FakeAiProvider.lastSystem).doesNotContain("سر المتجر الأول").contains("IS NOT AVAILABLE");
        assertThat(jdbc.sql("SELECT coalesce(reply_refs::text, '') || coalesce(reply_quote::text, '') FROM chat.messages WHERE conversation_id = :c AND content = :x").param("c", convB).param("x", "ده بكام\nوده؟").query(String.class).single()).doesNotContain("سر المتجر الأول");
    }

    @Test
    void aDuplicateWebhookDeliveryIsHandledOnce() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String pnid = pageId(), from = num(), id = "wamid.DUP" + uniq();
        connect(t, "WHATSAPP", pnid);
        FakeMetaGraph.reset();
        String hook = wa(pnid, from, id, "ده بكام", "wamid.QUOTED" + uniq());
        meta(hook).andExpect(status().isOk());
        int sent = FakeMetaGraph.SENT.size();
        meta(hook).andExpect(status().isOk());   // Meta delivers the same event again
        assertThat(FakeMetaGraph.SENT.size()).as("not answered twice").isEqualTo(sent);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND m.role = 'user'").param("t", tenant).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.message_mids WHERE tenant_id = :t AND provider_mid = :m").param("t", tenant).param("m", id).query(Long.class).single()).isEqualTo(1L);
    }

    // ---- Facebook / Instagram: the id of what the shop team sent ---------------------------------------------------------

    @Test
    void anOwnerReplyOnFacebookRecordsThePlatformIdAndTheCustomersNativeReplyToItIsUnderstood() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String pid = pageId(), user = "psid" + System.nanoTime();
        connect(t, "FACEBOOK", pid);
        FakeMetaGraph.reset();
        meta(page("page", pid, user, "mid.IN-1" + uniq(), "عندكم تيشيرت", "")).andExpect(status().isOk());
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'FACEBOOK'").param("t", tenant).query(UUID.class).single();

        String res = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + conv + "/reply", "{\"message\":\"هنبعتلك الصور حالا\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(res, "$.delivery")).isEqualTo("SENT");
        String mid = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text = 'هنبعتلك الصور حالا'").param("t", tenant).query(String.class).single();
        assertThat(mid).as("the id the platform returned, not a made-up one").startsWith("mid.TEST");
        assertThat(jdbc.sql("SELECT m.by_owner FROM chat.message_mids x JOIN chat.messages m ON m.id = x.message_id WHERE x.tenant_id = :t AND x.provider_mid = :m").param("t", tenant).param("m", mid).query(Boolean.class).single()).isTrue();

        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + conv + "/resume", null).andExpect(status().isOk());   // an owner reply pauses the assistant in that chat; resumed, it reads the customer's native reply
        FakeAiProvider.reset();
        meta(page("page", pid, user, "mid.IN-2" + uniq(), "وبكام ده", ",\"reply_to\":{\"mid\":\"%s\"}".formatted(mid))).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE SENDER: the shop team (a person, not you)").contains("ORIGINAL MESSAGE CONTENT: «هنبعتلك الصور حالا»");
    }

    @Test
    void aFailedOwnerSendRecordsNoPlatformId() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String pid = pageId(), user = "psid" + System.nanoTime();
        connect(t, "INSTAGRAM", pid);
        FakeMetaGraph.reset();
        meta(page("instagram", pid, user, "mid.IG-1" + uniq(), "عندكم شنطة", "")).andExpect(status().isOk());
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'INSTAGRAM'").param("t", tenant).query(UUID.class).single();
        FakeMetaGraph.FAIL_TEXT_SEND = true;
        try {
            String res = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + conv + "/reply", "{\"message\":\"رسالة فشل ارسالها\"}").andExpect(status().isOk()));
            assertThat(JsonPath.<String>read(res, "$.delivery")).isEqualTo("FAILED");
        } finally { FakeMetaGraph.FAIL_TEXT_SEND = false; }
        assertThat(jdbc.sql("SELECT count(*) FROM chat.message_mids WHERE tenant_id = :t AND part_text = 'رسالة فشل ارسالها'").param("t", tenant).query(Long.class).single()).isZero();
    }

    // ---- WhatsApp Cloud: the answer quotes the customer's message (documented context.message_id); Messenger does not ---------

    @Test
    void whatsAppCloudAnswersAThreadedMessageAsAContextualReplyAndFallsBackToPlainWhenRefusedAndMessengerIsNeverGivenAQuoteField() throws Exception {
        Tenant t = onboard("STORE");
        String pnid = pageId(), from = num();
        connect(t, "WHATSAPP", pnid);
        FakeMetaGraph.reset();
        String inbound = "wamid.IN" + uniq();
        meta(wa(pnid, from, inbound, "بكام ده", "wamid.OLD" + uniq())).andExpect(status().isOk());
        var send = FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body();
        assertThat(String.valueOf(send.get("context"))).as("quotes the customer's own message").isEqualTo("{message_id=" + inbound + "}");
        assertThat(send.get("type")).isEqualTo("text");

        // a message that is not a reply gets a plain answer
        meta(wa(pnid, from, "wamid.IN" + uniq(), "تمام شكرا", null)).andExpect(status().isOk());
        assertThat(FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body()).doesNotContainKey("context");

        // WhatsApp refuses the quote: the answer still reaches the customer, plainly
        FakeMetaGraph.FAIL_CONTEXT_SEND = true;
        try {
            int before = FakeMetaGraph.SENT.size();
            meta(wa(pnid, from, "wamid.IN" + uniq(), "وده؟", "wamid.OLD" + uniq())).andExpect(status().isOk());
            assertThat(FakeMetaGraph.SENT.size()).isGreaterThan(before);
            assertThat(FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body()).doesNotContainKey("context");
        } finally { FakeMetaGraph.FAIL_CONTEXT_SEND = false; }

        // Messenger: a reply_to in the customer's message changes nothing in what we send (no quote field is claimed to exist)
        String pid = pageId(), user = "psid" + System.nanoTime();
        connect(t, "FACEBOOK", pid);
        FakeMetaGraph.reset();
        meta(page("page", pid, user, "mid.IN-9" + uniq(), "ده بكام", ",\"reply_to\":{\"mid\":\"mid.OLD" + uniq() + "\"}")).andExpect(status().isOk());
        assertThat(FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body().toString()).doesNotContain("reply_to").doesNotContain("context");
    }

    // ---- the customer widget: the page around an old message ------------------------------------------------------------

    @Test
    void thePageAroundAnOldMessageIsOneBoundedReadAndNeverCrossesChatsOrShops() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        UUID ta = tenant(a);
        String ext = UUID.randomUUID().toString(), extOther = UUID.randomUUID().toString();
        onHost(a.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"اهلا\"}".formatted(ext)).andExpect(status().isOk());
        onHost(a.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"شات تاني\"}".formatted(extOther)).andExpect(status().isOk());
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", ta).param("e", ext).query(UUID.class).single();
        UUID other = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", ta).param("e", extOther).query(UUID.class).single();
        long first = jdbc.sql("SELECT min(id) FROM chat.messages WHERE conversation_id = :c").param("c", conv).query(Long.class).single();
        long target = seed(conv, "assistant", "الرسالة القديمة المطلوبة");
        for (int i = 0; i < 150; i++) seed(conv, i % 2 == 0 ? "user" : "assistant", "رسالة " + i);
        long replyRow = seed(conv, "user", "رد على القديمة");
        jdbc.sql("UPDATE chat.messages SET reply_to_id = :r, reply_quote = CAST('{\"sender\":\"assistant\",\"text\":\"x\"}' AS jsonb) WHERE id = :m").param("r", first).param("m", target + 3).update();
        long foreign = seed(other, "assistant", "رسالة من شات تاني");

        String win = body(onHost(a.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext + "&around=" + target, null).andExpect(status().isOk()));
        List<Number> ids = JsonPath.read(win, "$[*].id");
        assertThat(ids).hasSizeLessThanOrEqualTo(41);
        assertThat(ids.stream().map(Number::longValue)).isSorted().doesNotHaveDuplicates().contains(target).doesNotContain(replyRow);
        assertThat(JsonPath.<List<Object>>read(win, "$[?(@.id == " + (target + 3) + ")].replyTo.sender").get(0)).as("the stored original (the customer's first message) wins over the snapshot").isEqualTo("customer");
        // the history page itself (newest 60) does not contain it: this is the only way to reach it
        assertThat(JsonPath.<List<Number>>read(body(onHost(a.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext, null)), "$[*].id").stream().map(Number::longValue)).doesNotContain(target);
        // another chat's message, another shop's chat, a message that does not exist, a deleted one: nothing, and nothing says which
        assertThat(body(onHost(a.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext + "&around=" + foreign, null))).isEqualTo("[]");
        assertThat(body(onHost(b.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext + "&around=" + target, null))).isEqualTo("[]");
        assertThat(body(onHost(a.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext + "&around=999999999", null))).isEqualTo("[]");
        jdbc.sql("DELETE FROM chat.messages WHERE id = :i").param("i", target).update();
        assertThat(body(onHost(a.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext + "&around=" + target, null))).isEqualTo("[]");
    }

    // ---- memory beyond the window ---------------------------------------------------------------------------------------------

    @Test
    void whatTheCustomerSaidBeforeTheHistoryWindowIsGivenBackAndFillersAreNot() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        seed(conv, "user", "ميزانيتي ٦٠٠ جنيه ومقاسي XL");
        for (int i = 0; i < 40; i++) { seed(conv, "assistant", "رد " + i); seed(conv, "user", i % 3 == 0 ? "تمام" : "كلام تاني رقم " + i); }
        chat.reply(tenant, "WHATSAPP", ext, null, "طيب فاكر مقاسي", null);
        String system = FakeAiProvider.lastSystem;
        assertThat(system).contains("EARLIER IN THIS CHAT").contains("«ميزانيتي ٦٠٠ جنيه ومقاسي XL»").contains("the later one wins").contains("NOT current shop data");
        assertThat(system).doesNotContain("«تمام»");
        assertThat(system.split("EARLIER IN THIS CHAT")[1].split("\n  - «").length - 1).as("bounded: the first few and the latest ones").isLessThanOrEqualTo(16);

        // a chat that fits in the window has no digest
        String shortExt = num();
        UUID shortConv = start(tenant, shortExt);
        seed(shortConv, "user", "ميزانيتي قليلة");
        chat.reply(tenant, "WHATSAPP", shortExt, null, "طيب", null);
        assertThat(FakeAiProvider.lastSystem).doesNotContain("EARLIER IN THIS CHAT");
    }

    @Test
    void aReplyFarBeyondTheWindowStillGetsTheQuoteAndTheSavedMemoryTogether() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        long old = seed(conv, "assistant", "الجاكيت القطن مقاس L متاح");
        for (int i = 0; i < 60; i++) seed(conv, i % 2 == 0 ? "user" : "assistant", "ملء " + i);
        jdbc.sql("INSERT INTO chat.customer_memory (tenant_id, channel, external_id, notes) VALUES (:t, 'WHATSAPP', :e, '- بيفضل الألوان الغامقة')").param("t", tenant).param("e", ext).update();
        chat.reply(tenant, "WHATSAPP", ext, null, "ده بيتغسل؟", null, lines(List.of(ReplyRef.ofId(old)), "q1"));
        assertThat(FakeAiProvider.lastSystem).contains("WHAT YOU REMEMBER ABOUT THIS CUSTOMER").contains("بيفضل الألوان الغامقة").contains("ORIGINAL MESSAGE CONTENT: «الجاكيت القطن مقاس L متاح»").contains("EARLIER IN THIS CHAT");
    }

    // ---- an old quoted price is never current data ---------------------------------------------------------------------------

    @Test
    void anOldQuotedPriceTheModelRepeatsWithoutCheckingIsNeverSaidToTheCustomer() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        long old = seed(conv, "assistant", "السعر 1200 جنيه للجاكيت");
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("لسه بنفس السعر", turns -> Turn.model("أيوه السعر 1200 جنيه")));
        var reply = chat.reply(tenant, "WHATSAPP", ext, null, "لسه بنفس السعر؟", null, lines(List.of(ReplyRef.ofId(old)), "q2"));
        assertThat(FakeAiProvider.lastSystem).contains("is OLD").contains("CURRENT result");
        assertThat(reply.text()).as("an amount no tool returned in this reply is not the shop's word").doesNotContain("1200").doesNotContain("١٢٠٠");
    }

    @Test
    void replyingToAnOldOrderSummaryShowsItAndCreatesNoOrder() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = num();
        UUID conv = start(tenant, ext);
        long summary = seed(conv, "assistant", "ملخص الطلب: جاكيت × 1 — الإجمالي 350 جنيه\nأأكد الأوردر؟");
        for (int i = 0; i < 40; i++) seed(conv, i % 2 == 0 ? "user" : "assistant", "ملء " + i);
        chat.reply(tenant, "WHATSAPP", ext, null, "غير الكمية لاتنين", null, lines(List.of(ReplyRef.ofId(summary)), "q3"));
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: «ملخص الطلب: جاكيت × 1 — الإجمالي 350 جنيه").contains("أأكد الأوردر؟»").contains("Never create or change an order twice");
        assertThat((Integer) JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/orders", null)), "$.meta.total")).isZero();
    }

    @Test
    void anImageTheShopSentIsDescribedNotInvented() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        long img = jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, by_owner, media_url) VALUES (:c, 'assistant', '', TRUE, '/media/x.jpg') RETURNING id").param("c", conv).query(Long.class).single();
        chat.reply(tenant, "WHATSAPP", ext, null, "ده مقاوم للماء؟", null, lines(List.of(ReplyRef.ofId(img)), "q4"));
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: [an image was attached]").contains("the shop team (a person, not you)").contains("Never invent anything the original does not say");
    }

    @Test
    void aQuotedPriceThatIsStillTheCurrentCatalogPriceIsAllowedAndTheCustomersOwnEarlierBudgetIsToo() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":120000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated());
        String ext = num();
        UUID conv = start(tenant, ext);
        long old = seed(conv, "assistant", "السعر 1200 جنيه للكوباية");
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("لسه بنفس السعر", turns -> Turn.model("أيوه السعر 1200 جنيه")));
        var same = chat.reply(tenant, "WHATSAPP", ext, null, "لسه بنفس السعر؟", null, lines(List.of(ReplyRef.ofId(old)), "r1"));
        assertThat(same.text()).as("the shop really sells it at 1200 now: the old quote is confirmed by current data").contains("1200");

        // the customer's own budget from before the window is theirs to repeat
        String ext2 = num();
        UUID conv2 = start(tenant, ext2);
        seed(conv2, "user", "ميزانيتي 600 جنيه");
        for (int i = 0; i < 40; i++) seed(conv2, i % 2 == 0 ? "assistant" : "user", "ملء " + i);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("فاكر ميزانيتي", turns -> Turn.model("ميزانيتك 600 جنيه")));
        var budget = chat.reply(tenant, "WHATSAPP", ext2, null, "فاكر ميزانيتي؟", null);
        assertThat(budget.text()).contains("600");
    }

    @Test
    void theReplyContextReadsAreIndexedAndTakeMillisecondsOnAChatOfThousandsOfMessages() throws Exception {
        UUID tenant = tenant(onboard("STORE"));
        String ext = num();
        UUID conv = start(tenant, ext);
        long quoted = seed(conv, "assistant", "رسالة قديمة");
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) SELECT :c, CASE WHEN g % 2 = 1 THEN 'user' ELSE 'assistant' END, 'ملء رقم ' || g FROM generate_series(1, 4000) g").param("c", conv).update();
        jdbc.sql("ANALYZE chat.messages").update();
        long window = jdbc.sql("SELECT min(id) FROM (SELECT id FROM chat.messages WHERE conversation_id = :c ORDER BY id DESC LIMIT 24) x").param("c", conv).query(Long.class).single();
        String digest = "SELECT id, content FROM chat.messages WHERE conversation_id = :c AND role = 'user' AND id < :w ORDER BY id %s LIMIT :n";
        for (String order : new String[] {"ASC", "DESC"}) {
            var plan = String.join("\n", jdbc.sql("EXPLAIN " + digest.formatted(order)).param("c", conv).param("w", window).param("n", 12).query(String.class).list());
            assertThat(plan).as("digest " + order).contains("Index").doesNotContain("Seq Scan on messages");
        }
        for (int i = 0; i < 50; i++) { replies.around(tenant, conv, quoted, 2, 2); jdbc.sql(digest.formatted("DESC")).param("c", conv).param("w", window).param("n", 12).query().listOfRows(); }   // warm up
        int n = 300;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) replies.load(tenant, conv, quoted);
        double load = (System.nanoTime() - t0) / 1e6 / n;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) replies.around(tenant, conv, quoted, 2, 2);
        double around = (System.nanoTime() - t0) / 1e6 / n;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { jdbc.sql(digest.formatted("ASC")).param("c", conv).param("w", window).param("n", 4).query().listOfRows(); jdbc.sql(digest.formatted("DESC")).param("c", conv).param("w", window).param("n", 12).query().listOfRows(); }
        double dig = (System.nanoTime() - t0) / 1e6 / n;
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.printf("REPLY_PATH chat of 4000+ messages: quoted read %.2f ms, around-context %.2f ms, earlier-messages digest (2 reads) %.2f ms%n", load, around, dig);
        assertThat(load).isLessThan(25.0);
        assertThat(around).isLessThan(50.0);
        assertThat(dig).isLessThan(50.0);
    }
}

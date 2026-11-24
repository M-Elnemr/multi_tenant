package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import com.platform.chat.MessageReplies;
import com.platform.chat.ReplyRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A customer's reply to a specific message, end to end: stored, validated against the chat and the shop, read back by the backend however old the message is, shown to the assistant as
 * the message being replied to, and kept after a refresh. The assistant is scripted, so this proves what the assistant is GIVEN, not how well a real model understands it.
 */
class ReplyReferenceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired MessageReplies replies;

    final List<List<Turn>> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); seen.clear(); }

    UUID tenant(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String say(Tenant t, String conv, String text, Long replyTo) throws Exception {
        String json = replyTo == null ? "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text) : "{\"conversationId\":\"%s\",\"message\":\"%s\",\"replyToMessageId\":%d}".formatted(conv, text, replyTo);
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", json).andExpect(status().isOk()));
    }

    /** The scripted model answers and keeps the turns it was given. */
    void answer(String text) { FakeAiProvider.SCRIPT.add(turns -> { seen.add(new ArrayList<>(turns)); return Turn.model(text); }); }

    UUID convId(UUID tenant, String ext) { return jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'WEB' AND external_id = :e").param("t", tenant).param("e", ext).query(UUID.class).single(); }

    long count(UUID conv) { return jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", conv).query(Long.class).single(); }

    /** An earlier message of the chat, written straight to the table: the assistant's own prices would be rewritten by the money guard, and this is how an old message looks to a later reply. */
    long seed(UUID conv, String role, String text) { return jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x) RETURNING id").param("c", conv).param("r", role).param("x", text).query(Long.class).single(); }

    long idOf(UUID conv, String contains) { return jdbc.sql("SELECT id FROM chat.messages WHERE conversation_id = :c AND content LIKE :x ORDER BY id LIMIT 1").param("c", conv).param("x", "%" + contains + "%").query(Long.class).single(); }

    @Test
    void aReplyIsStoredWithItsOriginalAndTheAssistantGetsTheMessageBeingRepliedToLabelled() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = UUID.randomUUID().toString();
        answer("تمام");
        say(t, ext, "عايز جاكيت", null);
        UUID conv = convId(tenant, ext);
        long jacket = seed(conv, "assistant", "عندنا جاكيت قطن بالأسود والبيج بـ 1200 جنيه");
        seed(conv, "user", "الشحن كام"); seed(conv, "assistant", "الشحن ٦٠ جنيه");

        answer("متاح بالأزرق؟ (رد)");
        String reply = say(t, ext, "هل ده متاح بالأزرق؟", jacket);
        assertThat(JsonPath.<Number>read(reply, "$.userMessageId").longValue()).isPositive();

        // stored: the link, the snapshot of what was quoted, and it reads back after a refresh (the history endpoint)
        var row = jdbc.sql("SELECT reply_to_id, reply_quote::text AS q FROM chat.messages WHERE conversation_id = :c AND content = 'هل ده متاح بالأزرق؟'").param("c", conv).query().singleRow();
        assertThat(((Number) row.get("reply_to_id")).longValue()).isEqualTo(jacket);
        assertThat(String.valueOf(row.get("q"))).contains("جاكيت قطن").contains("assistant");
        String history = body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + ext, null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(history, "$[?(@.content == 'هل ده متاح بالأزرق؟')].replyTo.text").get(0).toString()).contains("جاكيت قطن");
        assertThat(JsonPath.<List<Object>>read(history, "$[?(@.content == 'هل ده متاح بالأزرق؟')].replyTo.sender").get(0)).isEqualTo("assistant");
        assertThat(JsonPath.<Number>read(history, "$[0].id").longValue()).isPositive();

        // the assistant was told which message this is about, with its sender and content, and no internal id
        String system = FakeAiProvider.lastSystem;
        assertThat(system).contains("MESSAGE BEING REPLIED TO").contains("ORIGINAL MESSAGE SENDER: you, the shop's assistant").contains("ORIGINAL MESSAGE CONTENT: «عندنا جاكيت قطن بالأسود والبيج بـ 1200 جنيه»")
                .contains("CUSTOMER'S LATEST MESSAGE").contains("CURRENT BUSINESS STATE").contains("call search_products / get_product and use the CURRENT result");
        assertThat(system).doesNotContain(String.valueOf(jacket) + " ").doesNotContain("reply_to");
        // the customer's own words are untouched: the checks that read them never see the quoted price
        assertThat(FakeAiProvider.customerText(seen.get(seen.size() - 1).get(seen.get(seen.size() - 1).size() - 1))).isEqualTo("هل ده متاح بالأزرق؟");   // the customer's own words, after the reply's context
    }

    @Test
    void anOldMessageFarOutsideTheHistoryWindowIsStillFoundAndShownWithItsNeighbours() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = UUID.randomUUID().toString();
        answer("تمام");
        say(t, ext, "بكام الجاكيت", null);
        UUID conv = convId(tenant, ext);
        long price = seed(conv, "assistant", "السعر 1,200 جنيه للجاكيت القطن");
        for (int i = 0; i < 45; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x)").param("c", conv).param("r", i % 2 == 0 ? "user" : "assistant").param("x", "filler " + i).update();

        answer("تمام، ممكن نتفق");
        say(t, ext, "ينفع تخليه 1000؟", price);
        List<Turn> given = seen.get(seen.size() - 1);
        assertThat(given.stream().map(FakeAiProvider::customerText)).as("the 24-message history window does NOT contain the old price message").noneMatch(x -> x != null && x.contains("السعر 1,200"));
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: «السعر 1,200 جنيه للجاكيت القطن»").contains("RELEVANT CONVERSATION CONTEXT").contains("customer: «بكام الجاكيت»");
    }

    @Test
    void aReferenceToAnotherChatOrAnotherShopIsRefusedAndNothingIsSaved() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        UUID ta = tenant(a), tb = tenant(b);
        String extA = UUID.randomUUID().toString(), extB = UUID.randomUUID().toString(), extA2 = UUID.randomUUID().toString();
        answer("SECRET of shop A");
        say(a, extA, "hello A", null);
        answer("hi B");
        say(b, extB, "hello B", null);
        answer("another chat of A");
        say(a, extA2, "hello again A", null);
        long secretA = idOf(convId(ta, extA), "SECRET of shop A");
        UUID convB = convId(tb, extB), convA2 = convId(ta, extA2);
        long beforeB = count(convB), beforeA2 = count(convA2);

        // shop B's customer names a message of shop A: refused, with a message that says nothing about it
        String res = body(onHost(b.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"x\",\"replyToMessageId\":%d}".formatted(extB, secretA)).andExpect(status().isBadRequest()));
        assertThat(res).contains("INVALID_REPLY_REFERENCE").doesNotContain("SECRET of shop A").doesNotContain("shop A");
        // another chat of the same shop: refused too
        onHost(a.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"x\",\"replyToMessageId\":%d}".formatted(extA2, secretA)).andExpect(status().isBadRequest());
        // a message that does not exist
        onHost(a.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"x\",\"replyToMessageId\":999999999}".formatted(extA2)).andExpect(status().isBadRequest());
        assertThat(count(convB)).isEqualTo(beforeB);
        assertThat(count(convA2)).isEqualTo(beforeA2);
        assertThat(FakeAiProvider.lastSystem).doesNotContain("SECRET of shop A");
        // the lookups themselves are scoped (what a model or a bug might try)
        assertThat(replies.load(tb, convB, secretA)).isNull();
        assertThat(replies.load(ta, convA2, secretA)).isNull();
        assertThat(replies.load(ta, convId(ta, extA), secretA)).isNotNull();
    }

    @Test
    void aDeletedOriginalFallsBackToTheSnapshotAndAMissingOneIsNeverGuessed() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = UUID.randomUUID().toString();
        answer("تمام");
        say(t, ext, "بكام", null);
        UUID conv = convId(tenant, ext);
        long msg = seed(conv, "assistant", "الجاكيت بـ 900 جنيه");
        answer("تمام");
        say(t, ext, "ده غالي", msg);
        jdbc.sql("DELETE FROM chat.messages WHERE id = :i").param("i", msg).update();   // the original is gone; the reply keeps its snapshot
        assertThat(jdbc.sql("SELECT reply_to_id FROM chat.messages WHERE conversation_id = :c AND content = 'ده غالي'").param("c", conv).query().singleRow().get("reply_to_id")).isNull();
        var q = replies.quotedBy(tenant, conv, idOf(conv, "ده غالي"));
        assertThat(q).isNotNull();
        assertThat(q.fromSnapshot()).isTrue();
        assertThat(q.text()).contains("الجاكيت بـ 900");

        // a platform id we know nothing about (a message sent before this system saw it): the assistant is told it is not available, and is not given a guess
        answer("ممكن توضحلي تقصد إيه؟");
        chat.reply(tenant, "WHATSAPP", "20109998877", null, "وده بكام؟", null, new ChatService.ReplyInput(List.of(ReplyRef.ofMid("wamid.UNKNOWN", null)), List.of()));
        assertThat(FakeAiProvider.lastSystem).contains("IS NOT AVAILABLE").contains("Never guess what it said").doesNotContain("wamid").doesNotContain("ORIGINAL MESSAGE CONTENT");
    }

    @Test
    void aNativeWhatsAppReplyFindsTheExactBubbleThroughThePlatformId() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String wa = "2010" + (10_000_000 + System.nanoTime() % 80_000_000);
        answer("أهلا بيك. عندنا شنطة جلد بني بـ 450 جنيه\n\nوعندنا كمان محفظة بـ 300 جنيه");
        var first = chat.reply(tenant, "WHATSAPP", wa, null, "عايز شنطة", null);
        UUID conv = first.conversationId();
        // the channel sent two bubbles and the platform gave an id to each
        replies.recordMid(tenant, conv, first.messageId(), "wamid.BUBBLE1", "أهلا بيك. عندنا شنطة جلد بني بـ 450 جنيه");
        replies.recordMid(tenant, conv, first.messageId(), "wamid.BUBBLE2", "وعندنا كمان محفظة بـ 300 جنيه");
        for (int i = 0; i < 30; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', :x)").param("c", conv).param("x", "later " + i).update();

        answer("تمام");
        chat.reply(tenant, "WHATSAPP", wa, null, "بتيجي بلون تاني؟", null, new ChatService.ReplyInput(List.of(ReplyRef.ofMid("wamid.BUBBLE1", null)), List.of("wamid.IN-1")));
        assertThat(FakeAiProvider.lastSystem).contains("ORIGINAL MESSAGE CONTENT: «أهلا بيك. عندنا شنطة جلد بني بـ 450 جنيه»").doesNotContain("«وعندنا كمان محفظة");   // the bubble she swiped, not the whole answer
        // the customer's own message got its platform id recorded too (a later reply to it would be found), and the link is stored
        assertThat(replies.idForMid(tenant, conv, "wamid.IN-1")).isNotNull();
        var stored = jdbc.sql("SELECT reply_to_mid FROM chat.messages WHERE conversation_id = :c AND content = 'بتيجي بلون تاني؟'").param("c", conv).query().singleRow();
        assertThat(stored.get("reply_to_mid")).isEqualTo("wamid.BUBBLE1");
        // the same id in ANOTHER shop's chat resolves to nothing
        Tenant other = onboard("STORE");
        assertThat(replies.idForMid(tenant(other), conv, "wamid.BUBBLE1")).isNull();
    }

    @Test
    void theOwnersReplyKeepsItsReferenceAndOldPagesCarryTheirQuotes() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = UUID.randomUUID().toString();
        answer("الجاكيت متاح");
        say(t, ext, "عندكم جاكيت؟", null);
        UUID conv = convId(tenant, ext);
        long customerMsg = idOf(conv, "عندكم جاكيت");
        // the owner answers that very message
        String res = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + conv + "/reply", "{\"message\":\"أيوه موجود\",\"replyToMessageId\":%d}".formatted(customerMsg)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(res, "$.messages[?(@.content == 'أيوه موجود')].replyTo.sender").get(0)).isEqualTo("customer");
        assertThat(JsonPath.<List<Object>>read(res, "$.messages[?(@.content == 'أيوه موجود')].replyTo.text").get(0)).isEqualTo("عندكم جاكيت؟");
        // another chat's message is refused
        String ext2 = UUID.randomUUID().toString();
        answer("x");
        say(t, ext2, "hello", null);
        long foreign = idOf(convId(tenant, ext2), "hello");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + conv + "/reply", "{\"message\":\"no\",\"replyToMessageId\":%d}".formatted(foreign)).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND content = 'no'").param("c", conv).query(Long.class).single()).isZero();
        // pagination: 80 more messages push the reply off the first page; the older page still shows its quote
        for (int i = 0; i < 80; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', :x)").param("c", conv).param("x", "filler " + i).update();
        String page1 = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + conv, null).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(page1, "$.hasOlder")).isTrue();
        long oldest = JsonPath.<Number>read(page1, "$.messages[0].id").longValue();
        String page2 = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + conv + "?before=" + oldest + "&read=false", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(page2, "$.messages[?(@.content == 'أيوه موجود')].replyTo.text").get(0)).isEqualTo("عندكم جاكيت؟");
    }

    @Test
    void theReplyLookupIsOneIndexedReadAndNeverLoadsTheChat() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String ext = UUID.randomUUID().toString();
        answer("تمام");
        say(t, ext, "أهلا", null);
        UUID conv = convId(tenant, ext);
        long id = seed(conv, "assistant", "رسالة قديمة");
        for (int i = 0; i < 3000; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', :x)").param("c", conv).param("x", "bulk " + i).update();
        jdbc.sql("ANALYZE chat.messages").update();
        var plan = jdbc.sql("EXPLAIN SELECT m.id FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE m.id = :id AND m.conversation_id = :c AND c.tenant_id = :t").param("id", id).param("c", conv).param("t", tenant).query(String.class).list();
        assertThat(String.join("\n", plan)).contains("Index").doesNotContain("Seq Scan on messages");
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) replies.load(tenant, conv, id);
        double ms = (System.nanoTime() - t0) / 1e6 / 200;
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.printf("REPLY_LOOKUP quoted-message read: %.2f ms each (chat of 3000+ messages)%n", ms);
        assertThat(ms).as("one indexed read, milliseconds").isLessThan(25.0);
    }
}

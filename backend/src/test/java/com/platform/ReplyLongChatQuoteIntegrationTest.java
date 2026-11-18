package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
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
 * A quoted message is resolved by its stored id, however long the chat is (28, 29, 50 and 100 messages around it), and the assistant is handed the product that message is about.
 * Several products and several variants live in the same chat; the quote always points at the message the customer chose, never at a later one about another product.
 * The assistant is scripted: this proves what the assistant is GIVEN (the quoted original, its product, the neighbours), not how well a real model reads it.
 */
class ReplyLongChatQuoteIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    final List<List<Turn>> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); seen.clear(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tenant(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    void product(Tenant t, String name, int price, String... colours) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        StringBuilder values = new StringBuilder(), variants = new StringBuilder();
        for (int i = 0; i < colours.length; i++) {
            if (i > 0) { values.append(','); variants.append(','); }
            values.append('"').append(colours[i]).append('"');
            variants.append("{\"sku\":\"S-%s\",\"priceMinor\":%d,\"optionValues\":{\"اللون\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}".formatted(uniq(), (price + i * 10) * 100, colours[i], branch));
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"WOMEN\",\"name\":\"%s\",\"options\":[{\"name\":\"اللون\",\"values\":[%s]}],\"variants\":[%s]}".formatted(name, values, variants)).andExpect(status().isCreated());
    }

    String say(Tenant t, String conv, String text, Long replyTo) throws Exception {
        String json = replyTo == null ? "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text) : "{\"conversationId\":\"%s\",\"message\":\"%s\",\"replyToMessageId\":%d}".formatted(conv, text, replyTo);
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", json).andExpect(status().isOk()));
    }

    void answer(String text) { FakeAiProvider.SCRIPT.add(turns -> { seen.add(new ArrayList<>(turns)); return Turn.model(text); }); }

    long seed(UUID conv, String role, String text) {
        return jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x) RETURNING id").param("c", conv).param("r", role).param("x", text).query(Long.class).single();
    }

    UUID conv(UUID tenant, String ext) { return jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'WEB' AND external_id = :e").param("t", tenant).param("e", ext).query(UUID.class).single(); }

    @Test
    void theQuotedMessageAndItsProductAreFoundAt28_29_50And100Messages() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String tag = uniq();
        String sport = "شنطة رياضة " + tag, school = "شنطة مدرسة " + tag, shirt = "تيشيرت قطن " + tag;
        product(t, sport, 620, "أسود", "أزرق");
        product(t, school, 550, "بنفسجي");
        product(t, shirt, 300, "أبيض", "أسود");
        for (int total : new int[] {28, 29, 50, 100}) {
            String ext = UUID.randomUUID().toString();
            answer("أهلا");
            say(t, ext, "السلام عليكم", null);
            UUID conv = conv(tenant, ext);
            long first = seed(conv, "user", sport + " بكام؟");                   // the message that will be quoted: a customer line naming ONE product
            seed(conv, "assistant", "ده بـ 620 جنيه");
            long have = jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", conv).query(Long.class).single();
            for (long i = have; i < total - 2; i++) {                            // later messages talk about OTHER products
                if (i % 2 == 0) seed(conv, "user", (i % 4 == 0 ? school : shirt) + " عندكم؟");
                else seed(conv, "assistant", "ايوه متاح يا فندم");
            }
            answer("ده سعره 620");
            String reply = say(t, ext, "اللي سألت عليه في الاول بكام؟", first);
            assertThat(JsonPath.<Number>read(reply, "$.userMessageId").longValue()).isPositive();

            // stored by stable id, and resolved to exactly that message
            Number linked = jdbc.sql("SELECT reply_to_id FROM chat.messages WHERE conversation_id = :c AND content = 'اللي سألت عليه في الاول بكام؟'").param("c", conv).query(Number.class).single();
            assertThat(linked.longValue()).as("messages around: " + total).isEqualTo(first);
            // the assistant is given the original, labelled, with ITS product (the sports bag), never a later product
            String system = FakeAiProvider.lastSystem;
            assertThat(system).as("messages around: " + total).contains("ORIGINAL MESSAGE CONTENT: «" + sport + " بكام؟»").contains("THE PRODUCT THIS MESSAGE IS ABOUT").contains(sport + " [");
            int at = system.indexOf("THE PRODUCT THIS MESSAGE IS ABOUT");
            String resolved = system.substring(at, system.indexOf('\n', at));
            assertThat(resolved).doesNotContain(school).doesNotContain(shirt);
            // above the 24-message history window the original is NOT in the history and still reaches the assistant through the block
            long inWindow = seen.get(seen.size() - 1).stream().filter(x -> x.text() != null && x.text().contains(sport + " بكام؟")).count();
            if (total >= 50) assertThat(inWindow).as("outside the history window at " + total).isZero();
        }
    }

    @Test
    void aQuoteOfAnEarlierAssistantAnswerWithTwoVariantsNamesTheProductNotAVariantGuess() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String tag = uniq();
        String shirt = "تيشيرت قطن " + tag, bag = "شنطة رياضة " + tag;
        product(t, shirt, 300, "أبيض", "أسود");
        product(t, bag, 620, "أسود");
        String ext = UUID.randomUUID().toString();
        answer("أهلا");
        say(t, ext, "السلام عليكم", null);
        UUID conv = conv(tenant, ext);
        long offer = seed(conv, "assistant", shirt + " متاح بالأبيض والأسود");
        for (int i = 0; i < 40; i++) seed(conv, i % 2 == 0 ? "user" : "assistant", i % 2 == 0 ? bag + " عندكم؟" : "ايوه متاح");
        answer("تمام");
        say(t, ext, "والاسود بكام؟", offer);
        String system = FakeAiProvider.lastSystem;
        assertThat(system).contains("ORIGINAL MESSAGE SENDER: you, the shop's assistant").contains("THE PRODUCT THIS MESSAGE IS ABOUT").contains(shirt + " [");
        int at = system.indexOf("THE PRODUCT THIS MESSAGE IS ABOUT");
        assertThat(system.substring(at, system.indexOf('\n', at))).doesNotContain(bag);
    }

    /** Seen in production (4 of 12 samples): the customer quotes the first answer and the model answers about the LAST product of the chat. Never sent: the quoted product's own data replaces it. */
    @Test
    void aReplyAboutAnotherProductThanTheQuotedOneIsNeverSentWhateverTheRewriteDoes() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String tag = uniq();
        String sport = "شنطة رياضة " + tag, school = "شنطة مدرسة " + tag;
        product(t, sport, 620, "أسود");
        product(t, school, 550, "بنفسجي");
        for (int variant = 0; variant < 2; variant++) {
            String ext = UUID.randomUUID().toString();
            answer("أهلا");
            say(t, ext, "السلام عليكم", null);
            FakeAiProvider.SCRIPT.clear();
            UUID conv = conv(tenant, ext);
            seed(conv, "user", sport + " بكام؟");
            long quoted = seed(conv, "assistant", sport + " بـ 620 جنيه يا فندم");
            seed(conv, "user", school + " بكام؟");
            seed(conv, "assistant", school + " بـ 550 جنيه يا فندم");
            // the model answers about the school bag; the rewrite then fails (variant 0) or repeats the same mistake (variant 1)
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أيوه يا فندم، السعر لسه ساري: " + school + " بـ 550 جنيه."));
            if (variant == 0) for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("TIMEOUT", "TIMEOUT", null); });
            else FakeAiProvider.SCRIPT.add(turns -> Turn.model("أيوه يا فندم، السعر لسه ساري: " + school + " بـ 550 جنيه."));
            String reply = JsonPath.read(say(t, ext, "هو السعر ده لسه ساري؟", quoted), "$.reply");
            assertThat(reply).as("variant " + variant).contains(sport).contains("620").doesNotContain(school);
        }
    }

    /** The «this detail is not in our data for X» note names the QUOTED product, never the latest one of the chat (seen in production: «باور بانك 10000» for a quote about the 20000). */
    @Test
    void theUnlistedDetailNoteNamesTheQuotedProduct() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String tag = uniq();
        String sport = "شنطة رياضة " + tag, school = "شنطة مدرسة " + tag;
        product(t, sport, 620, "أسود");
        product(t, school, 550, "بنفسجي");
        String ext = UUID.randomUUID().toString();
        answer("أهلا");
        say(t, ext, "السلام عليكم", null);
        FakeAiProvider.SCRIPT.clear();
        UUID conv = conv(tenant, ext);
        seed(conv, "user", sport + " بكام؟");
        long quoted = seed(conv, "assistant", sport + " بـ 620 جنيه يا فندم");
        seed(conv, "user", school + " بكام؟");
        seed(conv, "assistant", school + " بـ 550 جنيه يا فندم");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أيوه يا فندم، السعر لسه ساري، وبطاريتها بتشتغل 20 ساعة بشحنة واحدة."));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أيوه يا فندم، السعر لسه ساري، وبطاريتها بتشتغل 20 ساعة بشحنة واحدة."));
        String reply = JsonPath.read(say(t, ext, "هو السعر ده لسه ساري؟", quoted), "$.reply");
        assertThat(reply).doesNotContain("20 ساعة").doesNotContain(school);
        if (reply.contains("مش مكتوبة")) assertThat(reply).contains(sport);
    }
}

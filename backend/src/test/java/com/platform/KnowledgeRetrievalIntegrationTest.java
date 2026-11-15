package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import com.platform.chat.KnowledgeIndex;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The retrieval changes against real shop rows: word search with Arabizi, English and typos, a named product first, the reply's product kept in the question, and never another shop's rows. */
class KnowledgeRetrievalIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeIndex knowledge;
    @Autowired ChatService chat;

    @BeforeEach
    void on() { FakeEmbedder.on = true; FakeAiProvider.reset(); }

    @AfterEach
    void off() { FakeEmbedder.on = false; }

    private String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    private UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    private String product(Tenant t, String name, String description) throws Exception {
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"%s","variants":[{"sku":"S-%s","priceMinor":10000,"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(name, description, uniq(), branch);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.slug");
    }

    @Test
    void theWordSearchUnderstandsArabiziEnglishAndTyposAndTheIdentityStepFindsANamedProductOnlyInItsOwnShop() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        UUID ta = id(a), tb = id(b);
        String tee = product(a, "تيشيرت قطن اسود", "قطن 100% بيتغسل في الغسالة وممكن ينكمش لو اتغسل ساخن");
        String jacket = product(a, "جاكيت بافر شتوي رجالي", "جاكيت منفوخ دافي مقاوم للمطر الخفيف");
        product(b, "تيشيرت قطن اسود", "نفس الاسم عند متجر تاني ومواصفات مختلفة تماما");
        knowledge.sync(ta); knowledge.sync(tb);
        FakeEmbedder.on = false;   // the word search is the fallback path: exercise it on its own

        assertThat(knowledge.searchByWords(ta, "tshirt cotton eswed", "PRODUCT", 3)).extracting(KnowledgeIndex.Hit::sourceId).as("Arabizi / English words").contains(tee);
        assertThat(knowledge.searchByWords(ta, "winter jacket men", "PRODUCT", 3)).extracting(KnowledgeIndex.Hit::sourceId).contains(jacket);
        assertThat(knowledge.searchByWords(ta, "جاكيييت بافر", "PRODUCT", 3)).extracting(KnowledgeIndex.Hit::sourceId).as("repeated letters").contains(jacket);
        assertThat(knowledge.searchByWords(ta, "تيشرت قطن", "PRODUCT", 3)).extracting(KnowledgeIndex.Hit::sourceId).as("one letter missing").contains(tee);
        assertThat(knowledge.searchByWords(ta, "غسالة اطباق", "PRODUCT", 3)).as("a thing the shop does not sell finds nothing").isEmpty();

        assertThat(knowledge.identityHits(ta, "عايز جاكيت بافر شتوي رجالي بكام", 2)).extracting(KnowledgeIndex.Hit::sourceId).containsExactly(jacket);
        assertThat(knowledge.identityHits(ta, "3ayez tishirt cotton eswed", 2)).extracting(KnowledgeIndex.Hit::sourceId).as("Arabizi plus a typo: still the named product").containsExactly(tee);
        assertThat(knowledge.identityHits(ta, "عايز جاكيت", 2)).as("a category word names no product").isEmpty();
        assertThat(knowledge.identityHits(ta, "تيشيرت قطن اسود", 2)).extracting(KnowledgeIndex.Hit::body).as("same product name in two shops (even the same slug): each shop gets only its own").allMatch(body -> body.contains("بيتغسل في الغسالة") && !body.contains("نفس الاسم عند متجر تاني"));
        assertThat(knowledge.identityHits(tb, "تيشيرت قطن اسود", 2)).extracting(KnowledgeIndex.Hit::body).allMatch(body -> body.contains("نفس الاسم عند متجر تاني") && !body.contains("بيتغسل في الغسالة"));
        assertThat(knowledge.identityHits(tb, "جاكيت بافر شتوي رجالي", 2)).as("shop B has no such product, and never sees shop A's").isEmpty();
        assertThat(knowledge.searchByWords(tb, "winter jacket men", "PRODUCT", 3)).isEmpty();
        assertThat(knowledge.hybrid()).as("rank fusion stays off until it is measured with the production embedding").isFalse();
    }

    @Test
    void aVagueQuestionInReplyToAProductMessageReachesTheKnowledgeToolWithThatProductFirst() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        String tee = product(t, "تيشيرت قطن اسود", "قطن 100% بيتغسل في الغسالة وممكن ينكمش لو اتغسل ساخن");
        product(t, "جاكيت بافر شتوي رجالي", "جاكيت منفوخ دافي مقاوم للمطر الخفيف");
        product(t, "كوباية سيراميك", "كوباية قهوة سيراميك تتغسل في غسالة الاطباق");
        knowledge.sync(tenant);
        String ext = "2010" + (10_000_000 + System.nanoTime() % 80_000_000);
        UUID conv = chat.reply(tenant, "WHATSAPP", ext, null, "أهلا", null).conversationId();
        long old = jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', 'عندنا تيشيرت قطن اسود') RETURNING id").param("c", conv).query(Long.class).single();
        for (int i = 0; i < 40; i++) jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x)").param("c", conv).param("r", i % 2 == 0 ? "user" : "assistant").param("x", "ملء " + i).update();

        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("بينكمش", turns -> FakeAiProvider.call("shop_knowledge", Map.of("question", "هل بينكمش بعد الغسيل؟"))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("حسب بيانات المحل ممكن ينكمش لو اتغسل ساخن"));
        chat.reply(tenant, "WHATSAPP", ext, null, "هل بينكمش؟", null, new ChatService.ReplyInput(List.of(com.platform.chat.ReplyRef.ofId(old)), List.of("q1")));
        var result = FakeAiProvider.RESULTS.stream().filter(r -> "shop_knowledge".equals(r.name())).findFirst().orElseThrow().result();
        @SuppressWarnings("unchecked") List<Map<String, Object>> passages = (List<Map<String, Object>>) result.get("passages");
        assertThat(passages).isNotEmpty();
        assertThat(passages.get(0).get("slug")).as("the product the customer replied about comes first although the question never names it").isEqualTo(tee);
    }
}

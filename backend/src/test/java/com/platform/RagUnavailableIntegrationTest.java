package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.KnowledgeIndex;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * When search by meaning is down the seller never goes on as if the shop's knowledge had been searched: the model is told it is unavailable (an empty result is not "the shop has nothing"),
 * the tool stays in its list, and the reply is recorded for the health page. Found in the 2026-11-10 model comparison: 8 embedding timeouts made 35 of 480 conversations run without RAG, silently.
 */
class RagUnavailableIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeIndex knowledge;

    @BeforeEach
    void on() { FakeEmbedder.on = true; FakeEmbedder.resting = false; FakeEmbedder.failQueries = false; FakeAiProvider.reset(); }

    @AfterEach
    void off() { FakeEmbedder.on = false; FakeEmbedder.resting = false; FakeEmbedder.failQueries = false; }

    private String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    private UUID shop(Tenant t) throws Exception {
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"سماعة رقبة رياضية","brand":"Acme","description":"نيك باند بلوتوث للجري والرياضة، البطارية 20 ساعة","variants":[{"sku":"S-%s","priceMinor":59900,"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated());
        knowledge.sync(tenant);
        return tenant;
    }

    private List<Map<String, Object>> chat(Tenant t, String asked) throws Exception {
        String question = asked + " " + uniq();   // unique: a question whose vector is already cached is answered even while the provider is down
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "حاجة شيك لخطوبة " + uniq())));   // words match nothing: only meaning could find it (unique: a query vector already cached is served even while the provider is down)
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("shop_knowledge", Map.of("question", question)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("لحظة"));
        String conv = UUID.randomUUID().toString();
        lastConv = conv;
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, question)).andExpect(status().isOk());
        return FakeAiProvider.RESULTS.stream().map(r -> r.result()).toList();
    }

    private String lastConv;

    private long ragEvents(String conv) {
        return jdbc.sql("SELECT count(*) FROM chat.ai_events e JOIN chat.conversations c ON c.id = e.conversation_id WHERE c.external_id = :c AND e.kind = 'RAG_UNAVAILABLE'").param("c", conv).query(Long.class).single();
    }

    @Test
    void aFailingProviderIsToldToTheModelAndRecordedNeverSilentlyEmpty() throws Exception {
        Tenant t = onboard("STORE");
        shop(t);
        FakeEmbedder.failQueries = true;   // connect timeouts
        var results = chat(t, "السماعة الرياضية بطاريتها كام؟");
        var search = results.get(0);
        assertThat(search).containsEntry("searchByMeaning", "UNAVAILABLE");
        assertThat(String.valueOf(search.get("note"))).contains("temporarily unavailable").contains("never tell the customer we do not have it").doesNotContain("The shop has nothing matching");
        var knowledgeResult = results.get(1);
        assertThat(knowledgeResult).containsEntry("unavailable", true);
        assertThat(String.valueOf(knowledgeResult.get("note"))).contains("temporarily unavailable").doesNotContain("says nothing about this");
        assertThat(ragEvents(lastConv)).isEqualTo(1);   // once per reply, however many searches it made
    }

    @Test
    void aRestingProviderKeepsTheToolInTheListAndSaysUnavailable() throws Exception {
        Tenant t = onboard("STORE");
        shop(t);
        FakeEmbedder.resting = true;   // enabled() false, configured() true
        var results = chat(t, "السماعة الرياضية بطاريتها كام؟");
        assertThat(FakeAiProvider.lastTools().stream().map(d -> d.name())).contains("shop_knowledge");   // it used to vanish while the provider rested
        assertThat(results.get(1)).containsEntry("unavailable", true);
        assertThat(ragEvents(lastConv)).isEqualTo(1);
    }

    @Test
    void aWorkingProviderIsNotAnnotatedAndRecordsNothing() throws Exception {
        Tenant t = onboard("STORE");
        shop(t);
        var results = chat(t, "السماعة الرياضية بطاريتها كام؟");
        assertThat(results.get(0)).doesNotContainKey("searchByMeaning");
        assertThat(results.get(1)).doesNotContainKey("unavailable").containsKey("passages");
        assertThat(ragEvents(lastConv)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void whileSearchByMeaningIsDownTheShopsPassagesWithTheQuestionsWordsAreStillOfferedAndSaidToBeOnlyThat() throws Exception {
        Tenant t = onboard("STORE");
        shop(t);
        FakeEmbedder.failQueries = true;
        var results = chat(t, "السماعة الرياضية بطاريتها كام؟");
        var knowledgeResult = results.get(1);
        assertThat(knowledgeResult).containsEntry("unavailable", true).containsEntry("searchByMeaning", "UNAVAILABLE");
        var passages = (List<Map<String, Object>>) knowledgeResult.get("passages");
        assertThat(passages).isNotEmpty();
        assertThat(String.valueOf(passages.get(0).get("title"))).contains("سماعة");
        assertThat(String.valueOf(knowledgeResult.get("note"))).contains("may NOT be everything").contains("flag_unanswered");
        // a question with none of the shop's words stays empty and unavailable: the model is told it cannot confirm, never "the shop has nothing"
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("shop_knowledge", Map.of("question", "هل بتشحنوا للقمر؟ " + uniq())));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("لحظة"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"هل بتشحنوا للقمر؟\"}".formatted(UUID.randomUUID())).andExpect(status().isOk());
        var empty = FakeAiProvider.RESULTS.get(0).result();
        assertThat((List<?>) empty.get("passages")).isEmpty();
        assertThat(empty).containsEntry("unavailable", true);
        assertThat(String.valueOf(empty.get("note"))).contains("cannot confirm").doesNotContain("says nothing about this");
    }
}

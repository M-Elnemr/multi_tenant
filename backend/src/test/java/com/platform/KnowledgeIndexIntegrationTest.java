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

/** Search by meaning (RAG): the shop's products and texts are indexed by themselves, an edit re-embeds only that product, and the seller's tools find what the words alone miss. */
class KnowledgeIndexIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeIndex knowledge;

    @BeforeEach
    void on() { FakeEmbedder.on = true; FakeAiProvider.reset(); }

    @AfterEach
    void off() { FakeEmbedder.on = false; }

    private String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    private String product(Tenant t, String branch, String name, String description, long price) throws Exception {
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"%s","variants":[{"sku":"S-%s","priceMinor":%d,"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(name, description, uniq(), price, branch);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.id");
    }

    @Test
    void theShopIsIndexedByItselfAndFoundByMeaning() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        product(t, branch, "سماعة رقبة رياضية", "نيك باند بلوتوث للجري والرياضة، مقاومة للعرق، البطارية 20 ساعة", 59900);
        String jacket = product(t, branch, "جاكيت بافر شتوي", "جاكيت منفوخ دافي للشتا", 185000);
        product(t, branch, "كوباية سيراميك", "كوباية قهوة سيراميك", 9900);

        // the first sync writes and embeds every product (and no price or stock goes into the text)
        assertThat(knowledge.sync(tenant)).isGreaterThanOrEqualTo(3);
        var docs = jdbc.sql("SELECT source_id, body, embedding IS NOT NULL AS done FROM chat.knowledge_chunks WHERE tenant_id = :t AND source_type = 'PRODUCT'").param("t", tenant).query().listOfRows();
        assertThat(docs).hasSize(3).allMatch(d -> Boolean.TRUE.equals(d.get("done")));
        assertThat(docs.stream().map(d -> String.valueOf(d.get("body")))).noneMatch(b -> b.contains("599") || b.contains("1850"));
        assertThat(docs.stream().map(d -> String.valueOf(d.get("body"))).filter(b -> b.contains("نيك باند"))).hasSize(1);

        // nothing changed: nothing is embedded again
        int calls = FakeEmbedder.calls;
        assertThat(knowledge.sync(tenant)).isZero();
        assertThat(FakeEmbedder.calls).isEqualTo(calls);

        // found by meaning, with a spelling the catalog does not use
        assertThat(knowledge.productSlugs(tenant, "سماعه للرياضه والجري", 3)).first().asString().contains("سماعة");
        assertThat(knowledge.productSlugs(tenant, "جاكت بافر", 3)).first().asString().contains("جاكيت");

        // an edit re-embeds that product only; a removed product leaves the index
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/products/" + jacket, "{\"description\":\"جاكيت منفوخ دافي للشتا مع كابيشون\"}");
        jdbc.sql("UPDATE commerce.products SET status = 'ARCHIVED' WHERE tenant_id = :t AND name = 'كوباية سيراميك'").param("t", tenant).update();
        knowledge.sync(tenant);
        var after = jdbc.sql("SELECT source_id FROM chat.knowledge_chunks WHERE tenant_id = :t AND source_type = 'PRODUCT'").param("t", tenant).query(String.class).list();
        assertThat(after).hasSize(2).noneMatch(s -> s.contains("كوباية"));
        assertThat(knowledge.status(tenant)).containsEntry("enabled", true);

        // the seller: search_products finds the neckband for words its name does not hold, and shop_knowledge returns the product's own passage
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "سماعه للجري")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("shop_knowledge", Map.of("question", "السماعة الرياضية بطاريتها كام؟")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا سماعة رقبة رياضية"));
        ok(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز سماعه للجري\"}".formatted(conv)).andExpect(status().isOk()));
        var search = FakeAiProvider.RESULTS.stream().filter(r -> r.name().equals("search_products")).findFirst().orElseThrow().result();
        assertThat(String.valueOf(search.get("products"))).contains("سماعة رقبة رياضية");
        var passages = FakeAiProvider.RESULTS.stream().filter(r -> r.name().equals("shop_knowledge")).findFirst().orElseThrow().result();
        assertThat(String.valueOf(passages.get("passages"))).contains("20 ساعة");
        assertThat(FakeAiProvider.lastSystem).contains("PRODUCT EXPERT");
    }

    @Test
    void offWithoutAKeyTheSellerSearchesByWordsAndHasNoKnowledgeTool() throws Exception {
        FakeEmbedder.on = false;
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        assertThat(knowledge.search(tenant, "anything", null, 5)).isEmpty();
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("اهلا"));
        ok(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز قميص كتان مقاس كبير\"}".formatted(conv)).andExpect(status().isOk()));
        assertThat(FakeAiProvider.lastTools().stream().map(d -> d.name()).toList()).doesNotContain("shop_knowledge").contains("search_products", "recommend_related");
    }
}

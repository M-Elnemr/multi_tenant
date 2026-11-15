package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.KnowledgeIndex;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Search by meaning never crosses shops: two synthetic shops with different catalogs, searched in turn (so a cache keyed wrongly would leak), both ways, by meaning and by words. */
class KnowledgeTenantIsolationIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeIndex knowledge;

    @BeforeEach
    void on() { FakeEmbedder.on = true; FakeAiProvider.reset(); }

    @AfterEach
    void off() { FakeEmbedder.on = false; }

    private String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    private String product(Tenant t, String branch, String name, String description) throws Exception {
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"%s","variants":[{"sku":"S-%s","priceMinor":10000,"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(name, description, uniq(), branch);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.slug");
    }

    private UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    private String branch(Tenant t) throws Exception { return JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id"); }

    @Test
    void aShopNeverFindsAnotherShopsProductsOrTextsByMeaningOrByWords() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        String headphonesA = product(a, branch(a), "سماعة رقبة رياضية", "نيك باند بلوتوث للجري والرياضة مقاومة للعرق");
        String secretA = product(a, branch(a), "منتج سري للمتجر الأول", "SECRET-ONLY-IN-SHOP-A تفاصيل لا يجب أن يراها غيره");
        String mugB = product(b, branch(b), "كوباية سيراميك", "كوباية قهوة سيراميك للمتجر الثاني");
        UUID ta = id(a), tb = id(b);
        knowledge.sync(ta);
        knowledge.sync(tb);

        // alternate the shops so a cache keyed without the shop would hand one shop the other's vectors
        for (int i = 0; i < 3; i++) {
            List<String> forA = knowledge.productSlugs(ta, "سماعه للرياضه والجري", 6);
            List<String> forB = knowledge.productSlugs(tb, "سماعه للرياضه والجري", 6);
            assertThat(forA).contains(headphonesA).doesNotContain(mugB);
            assertThat(forB).as("shop B has no headphones: it must not be shown shop A's").doesNotContain(headphonesA, secretA);
            assertThat(knowledge.productSlugs(tb, "منتج سري", 6)).doesNotContain(secretA);
        }
        assertThat(knowledge.productSlugs(ta, "كوباية قهوة", 6)).doesNotContain(mugB);

        // the stored chunks are filed under their own shop only, and texts never mention the other
        var chunksA = jdbc.sql("SELECT body FROM chat.knowledge_chunks WHERE tenant_id = :t").param("t", ta).query(String.class).list();
        var chunksB = jdbc.sql("SELECT body FROM chat.knowledge_chunks WHERE tenant_id = :t").param("t", tb).query(String.class).list();
        assertThat(chunksB).noneMatch(c -> c.contains("SECRET-ONLY-IN-SHOP-A") || c.contains("نيك باند"));
        assertThat(chunksA).noneMatch(c -> c.contains("للمتجر الثاني"));
        // searching by words is scoped the same way
        assertThat(knowledge.searchByWords(tb, "SECRET-ONLY-IN-SHOP-A", "PRODUCT", 6)).isEmpty();
        // and a search for a shop that does not exist finds nothing
        assertThat(knowledge.productSlugs(UUID.randomUUID(), "سماعه", 6)).isEmpty();
    }
}

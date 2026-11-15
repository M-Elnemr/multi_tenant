package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The prompt's product list reads every product's details in one query: the text must be exactly what the one-by-one read gives, and never another shop's. */
class ProductDetailsBatchTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    List<String> products(Tenant t, int n) throws Exception {
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        List<String> slugs = new ArrayList<>();
        for (int i = 0; i < n; i++)
            slugs.add(JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"shirts-tops\",\"audience\":\"ALL\",\"name\":\"قميص %d %s\",\"variants\":[{\"sku\":\"B-%s\",\"priceMinor\":10000,\"stock\":[{\"branchId\":\"%s\",\"quantity\":4}]}]}".formatted(i, uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.slug"));
        return slugs;
    }

    @Test
    void theBatchedDetailsAreIdenticalToTheOneByOneOnesAndScopedToTheShop() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        UUID ta = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", a.slug()).query(UUID.class).single();
        UUID tb = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", b.slug()).query(UUID.class).single();
        List<String> sa = products(a, 6), sb = products(b, 1);
        // different kinds of details: specs only, tags only, size guide only, all three, a very long size guide, and none at all
        jdbc.sql("UPDATE commerce.products SET specs = CAST('[{\"k\":\"خامة\",\"v\":\"قطن 100%\"},{\"k\":\"مقاس\",\"v\":\"واسع\"}]' AS jsonb) WHERE tenant_id = :t AND slug = :s").param("t", ta).param("s", sa.get(0)).update();
        jdbc.sql("UPDATE commerce.products SET tags = ARRAY['صيفي','رجالي'] WHERE tenant_id = :t AND slug = :s").param("t", ta).param("s", sa.get(1)).update();
        jdbc.sql("UPDATE commerce.products SET size_guide = 'S 50 سم   M 52 سم\nL 54 سم' WHERE tenant_id = :t AND slug = :s").param("t", ta).param("s", sa.get(2)).update();
        jdbc.sql("UPDATE commerce.products SET specs = CAST('[{\"k\":\"المكونات\",\"v\":\"كتان\"}]' AS jsonb), tags = ARRAY['جديد'], size_guide = 'دليل' WHERE tenant_id = :t AND slug = :s").param("t", ta).param("s", sa.get(3)).update();
        jdbc.sql("UPDATE commerce.products SET size_guide = :g WHERE tenant_id = :t AND slug = :s").param("g", "طويل ".repeat(100)).param("t", ta).param("s", sa.get(4)).update();
        jdbc.sql("UPDATE commerce.products SET specs = CAST('[{\"k\":\"السر\",\"v\":\"خاص بالمتجر الثاني\"}]' AS jsonb) WHERE tenant_id = :t AND slug = :s").param("t", tb).param("s", sb.get(0)).update();

        Map<String, String> batch = chat.productDetailsFor(ta, sa);
        for (String slug : sa) assertThat(batch.getOrDefault(slug, "")).as("details of " + slug).isEqualTo(chat.productDetails(ta, slug));
        assertThat(batch.get(sa.get(0))).contains("خامة: قطن 100%");
        assertThat(batch.get(sa.get(5))).as("a product with no details").isEmpty();
        assertThat(chat.productDetailsFor(ta, List.of())).isEmpty();
        assertThat(chat.productDetailsFor(ta, sb)).as("another shop's slug asked through this shop").isEmpty();
        assertThat(chat.productDetailsFor(tb, sa)).as("this shop's slugs asked through another shop").isEmpty();
        assertThat(chat.allProducts(ta)).contains("خامة: قطن 100%").contains("tags: صيفي, رجالي").contains("size guide: S 50 سم M 52 سم L 54 سم").doesNotContain("خاص بالمتجر الثاني");
    }
}

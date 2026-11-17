package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.ChatService;
import com.platform.commerce.CatalogService;
import com.platform.commerce.StoreSearchService;
import com.platform.shared.Page;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The batched catalog digest is the SAME text as the per-product one it replaced (the model's input must not change), and never reads another shop's variants. */
class CatalogDigestEquivalenceTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired CatalogService catalog;
    @Autowired StoreSearchService search;

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String branch(Tenant t) throws Exception { return JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id"); }

    void product(Tenant t, String name, String taxonomy, int price, Integer compareAt, int stockBlack, int stockBrown, String branch) throws Exception {
        String u = uniq();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"%1$s","audience":"WOMEN","name":"%2$s","options":[{"name":"اللون","values":["أسود","بني"]},{"name":"المقاس","values":["M","L"]}],
                 "variants":[{"sku":"A-%3$s","priceMinor":%4$d,%5$s"optionValues":{"اللون":"أسود","المقاس":"M"},"stock":[{"branchId":"%6$s","quantity":%7$d}]},
                             {"sku":"B-%3$s","priceMinor":%4$d,"optionValues":{"اللون":"أسود","المقاس":"L"},"stock":[{"branchId":"%6$s","quantity":%7$d}]},
                             {"sku":"C-%3$s","priceMinor":%8$d,"optionValues":{"اللون":"بني","المقاس":"M"},"stock":[{"branchId":"%6$s","quantity":%9$d}]},
                             {"sku":"D-%3$s","priceMinor":%8$d,"optionValues":{"اللون":"بني","المقاس":"L"},"stock":[{"branchId":"%6$s","quantity":0}]}]}
                """.formatted(taxonomy, name, u, price, compareAt == null ? "" : "\"compareAtPriceMinor\":" + compareAt + ",", branch, stockBlack, price + 2000, stockBrown)).andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theBatchedDigestIsTheSameTextAsThePerProductOne() throws Exception {
        Tenant shop = onboard("STORE");
        UUID t = id(shop);
        String br = branch(shop);
        for (int i = 0; i < 6; i++) product(shop, "شنطة " + i + " " + uniq(), i % 2 == 0 ? "handbags" : "hats-caps", 50000 + i * 1000, i == 1 ? 70000 : null, 3 + i, i, br);
        Method all = ChatService.class.getDeclaredMethod("allProducts", UUID.class); all.setAccessible(true);
        Method line = ChatService.class.getDeclaredMethod("productLine", UUID.class, Map.class, String.class); line.setAccessible(true);
        Method detailsFor = ChatService.class.getDeclaredMethod("productDetailsFor", UUID.class, List.class); detailsFor.setAccessible(true);
        String batched = (String) all.invoke(chat, t);
        var rows = (List<Map<String, Object>>) search.list(t, Page.of(1, 60), new StoreSearchService.Filter(null, null, null, null, null, null, null, null, "name")).get("data");
        var details = (Map<String, String>) detailsFor.invoke(chat, t, rows.stream().map(r -> String.valueOf(r.get("slug"))).toList());
        StringBuilder legacy = new StringBuilder(batched.substring(0, batched.indexOf('\n') + 1));
        for (var r : rows) legacy.append((String) line.invoke(chat, t, r, details.getOrDefault(String.valueOf(r.get("slug")), "")));
        assertThat(rows).hasSize(6);
        assertThat(batched).isEqualTo(legacy.toString()).contains("PRICE BY OPTION").contains("before").contains("in stock");
    }

    @SuppressWarnings("unchecked")
    @Test
    void variantsComeBackInTheSameRowsAndOrderAsTheDetailAndNeverFromAnotherShop() throws Exception {
        Tenant a = onboard("STORE"), b = onboard("STORE");
        String brA = branch(a), brB = branch(b);
        String name = "شنطة مشتركة " + uniq();
        product(a, name, "handbags", 61000, null, 4, 2, brA);
        product(b, name, "handbags", 99000, null, 7, 7, brB);   // the same name (so the same slug) in another shop, other prices
        List<String> slugs = new ArrayList<>(jdbc.sql("SELECT slug FROM commerce.products WHERE tenant_id = :t").param("t", id(a)).query(String.class).list());
        var batch = catalog.activeVariantsBySlugs(id(a), slugs);
        for (String s : slugs) assertThat(batch.get(s)).isEqualTo(catalog.detailBySlug(id(a), s).get("variants"));
        assertThat(batch.values().stream().flatMap(List::stream).map(v -> String.valueOf(v.get("priceMinor"))).toList()).doesNotContain("99000", "101000");
        assertThat(catalog.activeVariantsBySlugs(id(a), List.of("no-such-slug")).get("no-such-slug")).isEmpty();
        assertThat(catalog.activeVariantsBySlugs(id(a), List.of())).isEmpty();
    }
}

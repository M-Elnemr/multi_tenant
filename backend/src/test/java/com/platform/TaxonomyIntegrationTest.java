package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The standard category list: owners pick from it, never create categories; standard colour/size/"for" filters. */
class TaxonomyIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String branch(Tenant t) throws Exception { return JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id"); }

    org.springframework.test.web.servlet.ResultActions create(Tenant t, String json) throws Exception { return onHost(t.host(), t.access(), "POST", "/api/v1/store/products", json); }

    String variants(String branch, String... skus) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < skus.length; i++) sb.append(i > 0 ? "," : "").append("{\"sku\":\"%s\",\"priceMinor\":10000,\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}".formatted(skus[i], branch));
        return sb.append("]").toString();
    }

    @Test
    void everyShopTypeHasAHomeAndTheListIsClean() throws Exception {
        Long unmapped = jdbc.sql("SELECT count(*) FROM commerce.store_categories sc WHERE sc.is_active AND sc.code NOT IN ('other') AND NOT EXISTS (SELECT 1 FROM commerce.business_type_taxonomy m WHERE m.business_code = sc.code)").query(Long.class).single();
        assertThat(unmapped).as("shop types without standard categories").isZero();
        Long dupes = jdbc.sql("SELECT count(*) FROM (SELECT commerce.norm_ar(name_ar) AS n, parent_id FROM commerce.taxonomy GROUP BY 1, 2 HAVING count(*) > 1) x").query(Long.class).single();
        assertThat(dupes).as("duplicate category names under one parent").isZero();
        Long orphans = jdbc.sql("SELECT count(*) FROM commerce.taxonomy t WHERE t.level > 1 AND t.parent_id IS NULL").query(Long.class).single();
        assertThat(orphans).isZero();
    }

    @Test
    void ownersSearchAndBrowseTheStandardList() throws Exception {
        Tenant t = onboard("STORE");
        String res = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/search?q=فساتين", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(res, "$[?(@.slug=='dresses')].breadcrumb").get(0)).isEqualTo("أزياء وملابس › ملابس › فساتين");
        assertThat(JsonPath.<List<Boolean>>read(res, "$[?(@.slug=='dresses')].appliesAudience").get(0)).isTrue();
        // letter variants and English both find it
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/search?q=dresses&lang=en", null).andExpect(jsonPath("$[0].slug").value("dresses")).andExpect(jsonPath("$[0].breadcrumb").value("Fashion › Clothing › Dresses"));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/search?q=اكسسوارات شعر", null).andExpect(jsonPath("$[0].slug").value("hair-accessories"));
        // browsing: top level, then children; the hidden fallback is never offered
        String roots = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/children", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(roots, "$[?(@.slug=='fashion')].slug")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(roots, "$[?(@.slug=='other')]")).isEmpty();
        String fashionId = String.valueOf(JsonPath.<List<Integer>>read(roots, "$[?(@.slug=='fashion')].id").get(0));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/children?parent=" + fashionId, null).andExpect(jsonPath("$[?(@.slug=='fashion-shoes')]").isNotEmpty());
        // suggestions follow the shop's own business type (the test shop is "fashion_men")
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/suggested", null).andExpect(jsonPath("$[?(@.slug=='shirts-tops')]").isNotEmpty());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/attributes", null).andExpect(jsonPath("$.colors.length()").value(19)).andExpect(jsonPath("$.sizes.APPAREL.length()").value(10));
    }

    @Test
    void productsMustUseAStandardCategoryAndSayWhoTheyAreFor() throws Exception {
        Tenant t = onboard("STORE");
        String v = variants(branch(t), "A-" + uniq());
        create(t, "{\"name\":\"No category\",\"variants\":" + v + "}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_REQUIRED"));
        create(t, "{\"name\":\"Typo\",\"taxonomySlug\":\"dreses\",\"audience\":\"ALL\",\"variants\":" + variants(branch(t), "B-" + uniq()) + "}").andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        create(t, "{\"name\":\"Top level\",\"taxonomySlug\":\"fashion\",\"audience\":\"ALL\",\"variants\":" + variants(branch(t), "C-" + uniq()) + "}").andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        create(t, "{\"name\":\"Fallback\",\"taxonomySlug\":\"other\",\"variants\":" + variants(branch(t), "D-" + uniq()) + "}").andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        create(t, "{\"name\":\"Dress\",\"taxonomySlug\":\"dresses\",\"variants\":" + variants(branch(t), "E-" + uniq()) + "}").andExpect(jsonPath("$.code").value("AUDIENCE_REQUIRED"));
        create(t, "{\"name\":\"Dress\",\"taxonomySlug\":\"dresses\",\"audience\":\"WOMEN\",\"condition\":\"BROKEN\",\"variants\":" + variants(branch(t), "F-" + uniq()) + "}").andExpect(status().isBadRequest());
        // a pot does not need "for whom"; a draft may wait for its category
        create(t, "{\"name\":\"Pot\",\"taxonomySlug\":\"cookware\",\"variants\":" + variants(branch(t), "G-" + uniq()) + "}").andExpect(status().isCreated()).andExpect(jsonPath("$.taxonomy[1].slug").value("cookware")).andExpect(jsonPath("$.itemCondition").value("NEW"));
        create(t, "{\"name\":\"Draft\",\"status\":\"DRAFT\",\"variants\":" + variants(branch(t), "H-" + uniq()) + "}").andExpect(status().isCreated());
        // changing the category later is validated the same way
        String id = JsonPath.read(ok(create(t, "{\"name\":\"Mover\",\"taxonomySlug\":\"cookware\",\"variants\":" + variants(branch(t), "I-" + uniq()) + "}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/products/" + id, "{\"taxonomyId\":" + 99999 + "}").andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
    }

    @Test
    void colourAndSizeAreStandardAndFilterable() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        // however the owner writes colour and size, the standard values are stored (and bad ones refused)
        String body = """
                {"name":"Cotton Tee","taxonomySlug":"shirts-tops","audience":"MEN",
                 "options":[{"name":"Color ","values":["Red","  أسود ","navy"]},{"name":"المقاس","values":["S","m"]}],
                 "variants":[
                  {"sku":"T1-%1$s","priceMinor":10000,"optionValues":{"Color ":"Red","المقاس":"S"},"stock":[{"branchId":"%2$s","quantity":5}]},
                  {"sku":"T2-%1$s","priceMinor":10000,"optionValues":{"Color ":"  أسود ","المقاس":"m"},"stock":[{"branchId":"%2$s","quantity":5}]},
                  {"sku":"T3-%1$s","priceMinor":10000,"optionValues":{"Color ":"navy","المقاس":"S"},"stock":[{"branchId":"%2$s","quantity":5}]},
                  {"sku":"T4-%1$s","priceMinor":10000,"optionValues":{"Color ":"Red","المقاس":"m"},"stock":[{"branchId":"%2$s","quantity":5}]},
                  {"sku":"T5-%1$s","priceMinor":10000,"optionValues":{"Color ":"  أسود ","المقاس":"S"},"stock":[{"branchId":"%2$s","quantity":5}]},
                  {"sku":"T6-%1$s","priceMinor":10000,"optionValues":{"Color ":"navy","المقاس":"m"},"stock":[{"branchId":"%2$s","quantity":5}]}]}
                """.formatted(uniq(), br);
        String res = ok(create(t, body).andExpect(status().isCreated()).andExpect(jsonPath("$.options[0].name").value("اللون")).andExpect(jsonPath("$.options[0].attribute").value("COLOR"))
                .andExpect(jsonPath("$.options[1].sizeScale").value("APPAREL")).andExpect(jsonPath("$.options[0].values[0]").value("أحمر")).andExpect(jsonPath("$.options[0].valueMeta[0].hex").value("#D62828")));
        assertThat(JsonPath.<List<String>>read(res, "$.options[1].values")).containsExactly("S", "M");
        assertThat(JsonPath.<String>read(res, "$.variants[0].comboKey")).isEqualTo("اللون=أحمر|المقاس=S");
        create(t, "{\"name\":\"Bad\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"Rd\"]}],\"variants\":[{\"sku\":\"X-" + uniq() + "\",\"priceMinor\":1,\"optionValues\":{\"اللون\":\"Rd\"}}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OPTION_VALUE"));
        // shoes: numeric sizes
        create(t, "{\"name\":\"Sneaker\",\"taxonomySlug\":\"sport-shoes\",\"audience\":\"MEN\",\"options\":[{\"name\":\"Size\",\"values\":[\"42\",\"43\"]}],\"variants\":[{\"sku\":\"SN1-" + uniq() + "\",\"priceMinor\":1,\"optionValues\":{\"Size\":\"42\"}},{\"sku\":\"SN2-" + uniq() + "\",\"priceMinor\":1,\"optionValues\":{\"Size\":\"43\"}}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.options[0].sizeScale").value("SHOE"));
        create(t, "{\"name\":\"Mug\",\"taxonomySlug\":\"tableware\",\"options\":[{\"name\":\"Capacity\",\"values\":[\"300ml\",\"500ml\"]}],\"variants\":[{\"sku\":\"M1-" + uniq() + "\",\"priceMinor\":1,\"optionValues\":{\"Capacity\":\"300ml\"}},{\"sku\":\"M2-" + uniq() + "\",\"priceMinor\":1,\"optionValues\":{\"Capacity\":\"500ml\"}}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.options[0].attribute").value(""));   // custom options stay free text

        String h = t.host();
        onHost(h, null, "GET", "/api/v1/shop/products?color=red", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, null, "GET", "/api/v1/shop/products?color=green", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(h, null, "GET", "/api/v1/shop/products?size=eu43", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, null, "GET", "/api/v1/shop/products?audience=MEN", null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(h, null, "GET", "/api/v1/shop/products?audience=WOMEN", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(h, null, "GET", "/api/v1/shop/products?category=fashion&color=black", null).andExpect(jsonPath("$.meta.total").value(1));
        String facets = ok(onHost(h, null, "GET", "/api/v1/shop/products/facets", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(facets, "$.colors[*].code")).containsExactly("black", "red", "navy");
        assertThat(JsonPath.<List<String>>read(facets, "$.sizes[?(@.scale=='APPAREL')].code")).containsExactly("s", "m");
        assertThat(JsonPath.<List<String>>read(facets, "$.audiences[*].audience")).containsExactly("MEN");
    }

    @Test
    void ownersSuggestMissingCategoriesAndThePlatformTeamDecides() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/taxonomy/requests", "{\"name\":\"فساتين\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));   // already in the list
        String unique = uniq();   // the platform list is shared by every test run, so each run suggests a name of its own
        String req = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/taxonomy/requests", "{\"name\":\"عبايات استقبال " + unique + "\",\"note\":\"للمناسبات\"}").andExpect(status().isOk()));
        String requestId = JsonPath.read(req, "$.id");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/requests", null).andExpect(jsonPath("$[0].status").value("PENDING"));
        // an owner cannot reach the platform tools
        onHost("platform.test", t.access(), "GET", "/api/v1/platform/taxonomy/requests", null).andExpect(status().isForbidden());

        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String admin = JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        onHost("platform.test", admin, "GET", "/api/v1/platform/taxonomy/requests?status=PENDING", null).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + requestId + "')]").isNotEmpty());
        String parent = String.valueOf(jdbc.sql("SELECT id FROM commerce.taxonomy WHERE slug = 'abayas-modest'").query(Integer.class).single());
        // (the admin files it as a sibling of the abayas category: parent = fashion-clothing)
        String clothing = String.valueOf(jdbc.sql("SELECT id FROM commerce.taxonomy WHERE slug = 'fashion-clothing'").query(Integer.class).single());
        assertThat(parent).isNotBlank();
        onHost("platform.test", admin, "POST", "/api/v1/platform/taxonomy/requests/" + requestId + "/decision", "{\"approve\":true,\"parentId\":" + clothing + ",\"nameAr\":\"عبايات استقبال " + unique + "\",\"nameEn\":\"Reception Abayas " + unique + "\",\"note\":\"ok\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.taxonomyId").isNumber());
        onHost("platform.test", admin, "POST", "/api/v1/platform/taxonomy/requests/" + requestId + "/decision", "{\"approve\":false}").andExpect(status().isConflict());
        // the new category is now pickable (and inherited the parent's "for whom" and size rules)
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/search?q=reception " + unique, null).andExpect(jsonPath("$[0].slug").value("reception-abayas-" + unique)).andExpect(jsonPath("$[0].appliesAudience").value(true)).andExpect(jsonPath("$[0].sizeScales[0]").value("APPAREL"));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/requests", null).andExpect(jsonPath("$[0].status").value("APPROVED"));
        // platform team can hide a category so owners stop picking it
        String newId = String.valueOf(jdbc.sql("SELECT id FROM commerce.taxonomy WHERE slug = :s").param("s", "reception-abayas-" + unique).query(Integer.class).single());
        onHost("platform.test", admin, "PATCH", "/api/v1/platform/taxonomy/" + newId, "{\"isHidden\":true}").andExpect(status().isOk());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/taxonomy/search?q=reception " + unique, null).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void existingShopCategoriesAreMappedToTheStandardList() throws Exception {
        // the data migration (V19) is a pure SQL rule; here we run the same matching on a legacy-style row
        Integer exact = jdbc.sql("SELECT t.id FROM commerce.taxonomy t WHERE t.level >= 2 AND NOT t.is_hidden AND (commerce.norm_ar(t.name_ar) = commerce.norm_ar(:n) OR lower(t.name_en) = lower(:n)) LIMIT 1").param("n", "فساتين").query(Integer.class).single();
        Integer contains = jdbc.sql("SELECT t.id FROM commerce.taxonomy t WHERE t.level >= 2 AND NOT t.is_hidden AND t.id < (SELECT id FROM commerce.taxonomy WHERE slug = 'other') AND commerce.norm_ar(t.name_ar) LIKE '%' || regexp_replace(commerce.norm_ar(:n), '^ال', '') || '%' ORDER BY t.level DESC, length(t.name_ar) LIMIT 1").param("n", "العبايات").query(Integer.class).single();
        assertThat(jdbc.sql("SELECT slug FROM commerce.taxonomy WHERE id = :i").param("i", exact).query(String.class).single()).isEqualTo("dresses");
        assertThat(jdbc.sql("SELECT slug FROM commerce.taxonomy WHERE id = :i").param("i", contains).query(String.class).single()).isEqualTo("abayas-modest");
    }
}

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
import org.springframework.test.web.servlet.ResultActions;

/** Ten designs for a shop with one to three products: chosen only while the shop has 1-3 active products (variants are not products), enforced by the backend, never removed from a published site. */
class SmallCatalogTemplatesIntegrationTest extends IntegrationTestBase {
    static final List<String> SMALL = List.of("solo", "vitrine", "capsule", "monolith", "pedestal", "spotlight", "launch", "signature", "curated", "origin");
    @Autowired JdbcClient jdbc;

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    UUID tid(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    void product(Tenant t, String name, int variants) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        StringBuilder values = new StringBuilder(), vs = new StringBuilder();
        for (int i = 0; i < variants; i++) {
            if (i > 0) { values.append(','); vs.append(','); }
            values.append("\"v").append(i).append('"');
            vs.append("{\"sku\":\"S-%s\",\"priceMinor\":50000,\"optionValues\":{\"اللون\":\"v%d\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}".formatted(uniq(), i, branch));
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"WOMEN\",\"name\":\"%s\",\"options\":[{\"name\":\"اللون\",\"values\":[%s]}],\"variants\":[%s]}".formatted(name, values, vs)).andExpect(status().isCreated());
    }

    ResultActions choose(Tenant t, String tpl) throws Exception { return onHost(t.host(), t.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"%s\"}".formatted(tpl)); }

    @Test
    void aShopWithNoProductsCannotChooseAndOneProductWithManyVariantsCan() throws Exception {
        Tenant t = onboard("STORE");
        choose(t, "solo").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SMALL_CATALOG_ONLY"));   // nothing to sell yet
        onHost(t.host(), t.access(), "GET", "/api/v1/store/template-options", null).andExpect(jsonPath("$.activeProducts").value(0)).andExpect(jsonPath("$.smallCatalogEligible").value(false));
        product(t, "شنطة " + uniq(), 12);   // one product, twelve variants: still one product
        onHost(t.host(), t.access(), "GET", "/api/v1/store/template-options", null).andExpect(jsonPath("$.activeProducts").value(1)).andExpect(jsonPath("$.smallCatalogEligible").value(true));
        for (String tpl : SMALL) choose(t, tpl).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT template FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tid(t)).query(String.class).single()).isEqualTo("origin");
    }

    @Test
    void threeProductsAreFineAFourthMakesNewSelectionImpossibleButTheSiteKeepsItsDesign() throws Exception {
        Tenant t = onboard("STORE");
        for (int i = 0; i < 3; i++) product(t, "منتج " + i + uniq(), 2);
        choose(t, "capsule").andExpect(status().isOk());
        product(t, "منتج رابع " + uniq(), 1);
        onHost(t.host(), t.access(), "GET", "/api/v1/store/template-options", null).andExpect(jsonPath("$.activeProducts").value(4)).andExpect(jsonPath("$.smallCatalogEligible").value(false)).andExpect(jsonPath("$.currentIsSmallCatalog").value(true));
        // the published design stays, even when the profile is saved again with the same design
        onHost(t.host(), t.access(), "GET", "/api/v1/store/profile", null).andExpect(jsonPath("$.template").value("capsule"));
        choose(t, "capsule").andExpect(status().isOk());
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.profile.template").value("capsule"));
        // another small design cannot be newly chosen now, and the ordinary designs still can
        choose(t, "monolith").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SMALL_CATALOG_ONLY"));
        choose(t, "atelier").andExpect(status().isOk());
        choose(t, "solo").andExpect(status().isBadRequest());   // and having left the small design, it cannot be re-chosen while the catalog is big
    }

    @Test
    void anInactiveProductDoesNotCountAndOtherTypesNeverGetTheseDesigns() throws Exception {
        Tenant t = onboard("STORE");
        for (int i = 0; i < 4; i++) product(t, "منتج " + i + uniq(), 1);
        choose(t, "solo").andExpect(status().isBadRequest());   // four active products
        jdbc.sql("UPDATE commerce.products SET status = 'ARCHIVED' WHERE id = (SELECT id FROM commerce.products WHERE tenant_id = :t ORDER BY created_at LIMIT 1)").param("t", tid(t)).update();
        choose(t, "solo").andExpect(status().isOk());           // an archived product is not sellable: three left
        String slug = "sv" + uniq();
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SERVICE\",\"name\":\"Salon\",\"slug\":\"%s\",\"ownerFirstName\":\"O\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, nextPhone()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Tenant svc = new Tenant(slug, JsonPath.read(res, "$.host"), "", JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
        choose(svc, "solo").andExpect(status().isBadRequest());   // a service business never gets them
    }
}

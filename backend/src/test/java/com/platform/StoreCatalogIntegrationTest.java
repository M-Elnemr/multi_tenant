package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Category tree, Arabic-aware search, filters, facets, branches and the public store profile. */
class StoreCatalogIntegrationTest extends IntegrationTestBase {
    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String category(Tenant t, String name, String parentId) throws Exception {
        String body = parentId == null ? "{\"name\":\"%s\"}".formatted(name) : "{\"name\":\"%s\",\"parentId\":\"%s\"}".formatted(name, parentId);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/categories", body).andExpect(status().isCreated())), "$.id");
    }

    String product(Tenant t, String branch, String name, String brand, String categoryId, long price, Long compareAt, int stock) throws Exception {
        String body = """
                {"name":"%s","brand":"%s","categoryId":%s,"variants":[{"sku":"S-%s","priceMinor":%d,"compareAtPriceMinor":%s,"stock":[{"branchId":"%s","quantity":%d}]}]}
                """.formatted(name, brand, categoryId == null ? "null" : "\"" + categoryId + "\"", uniq(), price, compareAt == null ? "null" : compareAt, branch, stock);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.id");
    }

    String branch(Tenant t) throws Exception { return JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id"); }

    @Test
    void parentCategoryIncludesSubcategoriesAndCountsRollUp() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        String fashion = category(t, "Fashion", null), men = category(t, "Men", fashion), shirts = category(t, "Shirts", men), toys = category(t, "Toys", null);
        product(t, br, "Blue Shirt", "Acme", shirts, 25000, null, 5);
        product(t, br, "Robot", "Fun", toys, 9000, null, 5);

        String all = ok(onHost(t.host(), null, "GET", "/api/v1/shop/products?category=fashion", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1)));
        assertThat((String) JsonPath.read(all, "$.data[0].name")).isEqualTo("Blue Shirt");
        onHost(t.host(), null, "GET", "/api/v1/shop/products?category=toys", null).andExpect(jsonPath("$.meta.total").value(1));

        String cats = ok(onHost(t.host(), null, "GET", "/api/v1/shop/categories", null));
        assertThat(JsonPath.<List<Integer>>read(cats, "$[?(@.slug=='fashion')].productCount").get(0)).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(cats, "$[?(@.slug=='shirts')].productCount").get(0)).isEqualTo(1);
    }

    @Test
    void categoryCannotMoveUnderItselfOrBeDeletedWhileInUse() throws Exception {
        Tenant t = onboard("STORE");
        String a = category(t, "A", null), b = category(t, "B", a);
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/categories/" + a, "{\"parentId\":\"%s\",\"moveToParent\":true}".formatted(b)).andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/categories/" + a, null).andExpect(status().isConflict());
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/categories/" + b, "{\"moveToParent\":true}").andExpect(status().isOk());   // B becomes top level
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/categories/" + b, null).andExpect(status().isOk());
    }

    @Test
    void arabicSearchIgnoresDiacriticsAndLetterVariants() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        product(t, br, "مَلابِس أطفال قطن", "نون", null, 15000, null, 3);
        product(t, br, "حقيبة سفر", "نون", null, 50000, null, 3);
        // alef forms, ta-marbuta/ha and tashkeel are all folded together
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=" + "ملابس اطفال", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=" + "حقيبه", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=" + "نون", null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=zzz", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(t.host(), null, "GET", "/api/v1/shop/search/suggest?q=" + "حقيب", null).andExpect(status().isOk()).andExpect(jsonPath("$.products.length()").value(1));
    }

    @Test
    void priceBrandStockSaleFiltersSortingAndFacets() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        product(t, br, "Cheap Tee", "Acme", null, 10000, null, 5);
        product(t, br, "Mid Tee", "Acme", null, 20000, 30000L, 5);
        product(t, br, "Pricey Tee", "Zed", null, 40000, null, 0);
        String h = t.host();
        onHost(h, null, "GET", "/api/v1/shop/products?minPrice=15000&maxPrice=45000", null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(h, null, "GET", "/api/v1/shop/products?brand=Zed", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, null, "GET", "/api/v1/shop/products?inStock=true", null).andExpect(jsonPath("$.meta.total").value(2));
        String sale = ok(onHost(h, null, "GET", "/api/v1/shop/products?onSale=true", null).andExpect(jsonPath("$.meta.total").value(1)));
        assertThat(JsonPath.<Number>read(sale, "$.data[0].discountPct").intValue()).isEqualTo(33);
        String asc = ok(onHost(h, null, "GET", "/api/v1/shop/products?sort=price_asc", null));
        assertThat((String) JsonPath.read(asc, "$.data[0].name")).isEqualTo("Cheap Tee");
        String desc = ok(onHost(h, null, "GET", "/api/v1/shop/products?sort=price_desc", null));
        assertThat((String) JsonPath.read(desc, "$.data[0].name")).isEqualTo("Pricey Tee");
        onHost(h, null, "GET", "/api/v1/shop/products/facets", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMinMinor").value(10000)).andExpect(jsonPath("$.priceMaxMinor").value(40000)).andExpect(jsonPath("$.brands.length()").value(2));
        onHost(h, null, "GET", "/api/v1/shop/home", null).andExpect(status().isOk()).andExpect(jsonPath("$.newest.length()").value(3)).andExpect(jsonPath("$.offers.length()").value(1));
    }

    @Test
    void branchesAndPublicProfileShowTheShopToCustomers() throws Exception {
        Tenant t = onboard("STORE");
        String h = t.host();
        onHost(h, t.access(), "POST", "/api/v1/store/branches", """
                {"name":"Downtown","code":"DT1","phone":"01011112222","whatsapp":"01011112222","address":"5 Tahrir St","city":"Cairo","governorateCode":"CAI","area":"Downtown","isPickup":true,
                 "workingHours":{"sat":{"open":"09:00","close":"21:00","closed":false}}}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.governorateCode").value("CAI")).andExpect(jsonPath("$.isPickup").value(true)).andExpect(jsonPath("$.workingHours.sat.open").value("09:00"));
        onHost(h, t.access(), "PATCH", "/api/v1/store/profile", """
                {"supportPhone":"01011112222","whatsapp":"01011112222","addressText":"5 Tahrir St","facebookUrl":"https://facebook.com/shop","isOpen":false,"closedMessage":"Back soon","minOrderMinor":5000,"taxId":"123-456-789","announcement":"Free delivery"}""")
                .andExpect(status().isOk());
        onHost(h, t.access(), "PATCH", "/api/v1/store/profile", "{\"facebookUrl\":\"javascript:alert(1)\"}").andExpect(status().isBadRequest());

        String pub = ok(onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(status().isOk()).andExpect(jsonPath("$.profile.isOpen").value(false))
                .andExpect(jsonPath("$.profile.whatsapp").value("01011112222")).andExpect(jsonPath("$.profile.announcement").value("Free delivery")));
        assertThat(pub).doesNotContain("123-456-789");     // the tax id is for invoices, not for the public profile
        onHost(h, null, "GET", "/api/v1/shop/branches", null).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code=='DT1')].isPickup").value(true));

        String id = JsonPath.<List<String>>read(ok(onHost(h, t.access(), "GET", "/api/v1/store/branches", null)), "$[?(@.code=='DT1')].id").get(0);
        onHost(h, t.access(), "PATCH", "/api/v1/store/branches/" + id, "{\"isActive\":false}").andExpect(status().isOk());
        onHost(h, null, "GET", "/api/v1/shop/branches", null).andExpect(jsonPath("$[?(@.code=='DT1')]").isEmpty());
    }

    @Test
    void productExtrasAndRelatedProducts() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        String cat = category(t, "Shoes", null);
        String p1 = product(t, br, "Runner One", "Acme", cat, 30000, null, 5);
        product(t, br, "Runner Two", "Acme", cat, 32000, null, 5);
        String slug = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/products/" + p1, null)), "$.slug");
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/products/" + p1 + "/extras",
                "{\"badge\":\"new\",\"tags\":[\"running\",\"sport\"],\"specs\":[{\"k\":\"Weight\",\"v\":\"300g\"}],\"isFeatured\":true,\"sizeGuide\":\"EU 40-45\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.badge").value("NEW")).andExpect(jsonPath("$.tags.length()").value(2)).andExpect(jsonPath("$.specs[0].k").value("Weight"));
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/products/" + p1 + "/extras", "{\"badge\":\"nonsense\"}").andExpect(status().isBadRequest());
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=sport", null).andExpect(jsonPath("$.meta.total").value(1));   // tags are searchable
        onHost(t.host(), null, "GET", "/api/v1/shop/home", null).andExpect(jsonPath("$.featured.length()").value(1));
        onHost(t.host(), null, "GET", "/api/v1/shop/products/" + slug + "/related", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Runner Two"));
    }
}

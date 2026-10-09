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

    /** A product filed under a standard category (slug; default "shirts-tops"). */
    String product(Tenant t, String branch, String name, String brand, String slug, long price, Long compareAt, int stock) throws Exception {
        String body = """
                {"name":"%s","brand":"%s","taxonomySlug":"%s","audience":"ALL","variants":[{"sku":"S-%s","priceMinor":%d,"compareAtPriceMinor":%s,"stock":[{"branchId":"%s","quantity":%d}]}]}
                """.formatted(name, brand, slug == null ? "shirts-tops" : slug, uniq(), price, compareAt == null ? "null" : compareAt, branch, stock);
        return JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.id");
    }

    String branch(Tenant t) throws Exception { return JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id"); }

    @Test
    void standardCategoriesRollUpAndFilterBySubtree() throws Exception {
        Tenant t = onboard("STORE");
        String br = branch(t);
        product(t, br, "Blue Dress", "Acme", "dresses", 25000, null, 5);
        product(t, br, "Robot", "Fun", "educational-toys", 9000, null, 5);

        String all = ok(onHost(t.host(), null, "GET", "/api/v1/shop/products?category=fashion", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1)));
        assertThat((String) JsonPath.read(all, "$.data[0].name")).isEqualTo("Blue Dress");
        onHost(t.host(), null, "GET", "/api/v1/shop/products?category=toys", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?category=football", null).andExpect(jsonPath("$.meta.total").value(0));

        // only categories that lead to products are offered, with counts that include everything below them
        String cats = ok(onHost(t.host(), null, "GET", "/api/v1/shop/categories", null));
        assertThat(JsonPath.<List<Integer>>read(cats, "$[?(@.slug=='fashion')].productCount").get(0)).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(cats, "$[?(@.slug=='fashion-clothing')].productCount").get(0)).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(cats, "$[?(@.slug=='dresses')].productCount").get(0)).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(cats, "$[?(@.slug=='football')]")).isEmpty();
        assertThat(JsonPath.<List<String>>read(ok(onHost(t.host(), null, "GET", "/api/v1/shop/categories?lang=en", null)), "$[?(@.slug=='dresses')].name").get(0)).isEqualTo("Dresses");
        assertThat(JsonPath.<List<String>>read(cats, "$[?(@.slug=='dresses')].name").get(0)).isEqualTo("فساتين");
    }

    @Test
    void shopsCannotCreateOrChangeCategories() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/categories", "{\"name\":\"قمصان\"}").andExpect(status().is4xxClientError());
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/categories/" + java.util.UUID.randomUUID(), null).andExpect(status().is4xxClientError());
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
        String cat = "casual-shoes";
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

    @Test
    void csvImportUsesStandardCategoriesAndExportRoundTrips() throws Exception {
        Tenant t = onboard("STORE");
        String csv = "name,brand,category,audience,condition,sku,price,compare_at_price,stock,description,status\r\n"
                + "قميص قطن,نور,أزياء وملابس > ملابس > قمصان وتيشيرتات,رجالي,جديد,SH-1,450.50,600,7,\"وصف, بفاصلة\",ACTIVE\r\n"
                + "حقيبة,نون,حقائب يد,نسائي,,,900,,3,,ACTIVE\r\n"
                + "بدون سعر,,,,,,,,,,\r\n"
                + "قميص غلط,نور,قمسان,رجالي,,SH-2,100,,1,,ACTIVE\r\n";   // a misspelt category is refused, with a hint, never created
        String body = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("csv", csv));
        String res = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products/import", body).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(2)).andExpect(jsonPath("$.errors.length()").value(2)));
        assertThat((Integer) JsonPath.read(res, "$.errors[0].row")).isEqualTo(4);
        assertThat((String) JsonPath.read(res, "$.errors[1].message")).contains("قمسان");
        onHost(t.host(), null, "GET", "/api/v1/shop/products?category=fashion", null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?q=قميص", null).andExpect(jsonPath("$.data[0].minPriceMinor").value(45050)).andExpect(jsonPath("$.data[0].compareAtMinor").value(60000));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?audience=WOMEN", null).andExpect(jsonPath("$.meta.total").value(1));
        // the web proxy always asks for JSON; the CSV must still be served
        String out = ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/store/products/export.csv").header("Host", t.host()).header("Authorization", "Bearer " + t.access()).header("Accept", "application/json")).andExpect(status().isOk()));
        assertThat(out).contains("أزياء وملابس > ملابس > قمصان وتيشيرتات").contains("450.50").contains("\"وصف, بفاصلة\"");
    }

    @Test
    void productPicturesCanBeAddedReorderedAndRemoved() throws Exception {
        Tenant t = onboard("STORE");
        String pid = product(t, branch(t), "Lamp", "Acme", null, 10000, null, 3);
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        String[] files = new String[2];
        for (int i = 0; i < 2; i++) {
            String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/files/presign", "{\"filename\":\"a.png\",\"contentType\":\"image/png\",\"size\":" + png.length + ",\"category\":\"PRODUCT_IMAGE\"}").andExpect(status().isOk()));
            files[i] = JsonPath.read(p, "$.fileId");
            String up = JsonPath.read(p, "$.uploadUrl");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(up).header("Host", t.host()).contentType("image/png").content(png)).andExpect(status().is2xxSuccessful());
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products/" + pid + "/media", "{\"fileId\":\"" + files[0] + "\",\"altText\":\"one\"}").andExpect(status().isOk()).andExpect(jsonPath("$.media.length()").value(1));
        String two = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products/" + pid + "/media", "{\"fileId\":\"" + files[1] + "\"}").andExpect(jsonPath("$.media.length()").value(2)));
        String first = JsonPath.read(two, "$.media[0].id"), second = JsonPath.read(two, "$.media[1].id");
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/products/" + pid + "/media/order", "{\"ids\":[\"" + second + "\"]}").andExpect(jsonPath("$.media[0].id").value(second)).andExpect(jsonPath("$.media[1].id").value(first));
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/products/" + pid + "/media/" + second, null).andExpect(jsonPath("$.media.length()").value(1));
        onHost(t.host(), null, "GET", "/api/v1/shop/products?pageSize=5", null).andExpect(jsonPath("$.data[0].imageUrl").isNotEmpty());
    }
}

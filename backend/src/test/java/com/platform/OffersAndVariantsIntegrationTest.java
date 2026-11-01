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

/** Editing a product's options/variants/stock, bulk stock, field-level errors, every offer type in checkout, and the order date filters. */
class OffersAndVariantsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String branch, String product, String slug, String vS, String vM) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String u = uniq();
        String res = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"shirts-tops","audience":"MEN","name":"Tee %1$s","options":[{"name":"Size","values":["S","M"]}],
                 "variants":[{"sku":"S-%1$s","priceMinor":10000,"optionValues":{"Size":"S"},"stock":[{"branchId":"%2$s","quantity":5}]},
                             {"sku":"M-%1$s","priceMinor":10000,"optionValues":{"Size":"M"},"stock":[{"branchId":"%2$s","quantity":5}]}]}
                """.formatted(u, branch)).andExpect(status().isCreated()));
        return new Shop(t, branch, JsonPath.read(res, "$.id"), JsonPath.read(res, "$.slug"),
                JsonPath.<List<String>>read(res, "$.variants[?(@.sku =~ /S-.*/)].id").get(0), JsonPath.<List<String>>read(res, "$.variants[?(@.sku =~ /M-.*/)].id").get(0));
    }

    String shipping(Shop s) throws Exception {
        return JsonPath.<List<String>>read(ok(onHost(s.t().host(), null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[?(@.type=='FIXED')].id").get(0);
    }

    String cart(Shop s, String variant, int qty) throws Exception {
        return """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona","phone":"01011112222","addressLine1":"1 Tahrir St","city":"Cairo"}}
                """.formatted(variant, qty, shipping(s));
    }

    @Test
    void editingOptionsVariantsAndStockKeepsHistory() throws Exception {
        Shop s = shop();
        String h = s.t().host(), a = s.t().access();
        // field errors: each bad cell is named, so the form can point at it
        onHost(h, a, "PUT", "/api/v1/store/products/" + s.product() + "/variants", """
                {"options":[{"name":"Size","values":["S"]}],"variants":[{"sku":"","priceMinor":-5,"optionValues":{"Size":"S"}}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['variants[0].sku']").exists()).andExpect(jsonPath("$.fields['variants[0].priceMinor']").exists());
        onHost(h, a, "POST", "/api/v1/store/products", "{\"name\":\"\",\"variants\":[{\"sku\":\"\",\"priceMinor\":100}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields.taxonomyId").exists()).andExpect(jsonPath("$.fields['variants[0].sku']").exists());

        // S stays (stock set to 9), M is dropped, L is new with 4 pieces
        String res = ok(onHost(h, a, "PUT", "/api/v1/store/products/" + s.product() + "/variants", """
                {"options":[{"name":"Size","values":["S","L"]}],"variants":[
                  {"id":"%s","sku":"S-new","priceMinor":12000,"optionValues":{"Size":"S"},"stock":[{"branchId":"%s","quantity":9}]},
                  {"sku":"L-new-%s","priceMinor":13000,"optionValues":{"Size":"L"},"stock":[{"branchId":"%s","quantity":4}]}]}
                """.formatted(s.vS(), s.branch(), uniq(), s.branch())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(res, "$.variants[?(@.status=='ACTIVE')].id")).hasSize(2).contains(s.vS());
        assertThat(JsonPath.<List<String>>read(res, "$.variants[?(@.status=='INACTIVE')].id")).containsExactly(s.vM());
        assertThat(JsonPath.<List<Integer>>read(res, "$.variants[?(@.id=='" + s.vS() + "')].stock[0].quantityOnHand").get(0)).isEqualTo(9);
        assertThat(JsonPath.<List<Object>>read(res, "$.options[0].values")).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.inventory_movements WHERE variant_id = :v AND type = 'ADJUSTMENT' AND quantity_delta = 4").param("v", UUID.fromString(s.vS())).query(Long.class).single()).isEqualTo(1);
        // the storefront only shows the live sizes
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slug(), null).andExpect(jsonPath("$.variants.length()").value(2));

        // bulk: +3 for every size of the product in one go
        String l = JsonPath.<List<String>>read(res, "$.variants[?(@.sku =~ /L-new.*/)].id").get(0);
        onHost(h, a, "POST", "/api/v1/store/inventory/bulk", """
                [{"branchId":"%1$s","variantId":"%2$s","delta":3},{"branchId":"%1$s","variantId":"%3$s","setTo":1}]""".formatted(s.branch(), s.vS(), l))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(2));
        String inv = ok(onHost(h, a, "GET", "/api/v1/store/inventory?q=Tee", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(inv, "$.data[?(@.variantId=='" + s.vS() + "')].quantityOnHand").get(0)).isEqualTo(12);
        assertThat(JsonPath.<List<Integer>>read(inv, "$.data[?(@.variantId=='" + l + "')].quantityOnHand").get(0)).isEqualTo(1);
        onHost(h, a, "GET", "/api/v1/store/inventory/movements?from=2020-01-01&type=ADJUSTMENT", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(2));
        onHost(h, a, "GET", "/api/v1/store/inventory/movements?from=2020-02-01&to=2020-01-01", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.to").exists());
    }

    @Test
    void offersPriceTheCartAndShowOnTheStorefront() throws Exception {
        Shop s = shop();
        String h = s.t().host(), a = s.t().access();
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"\",\"type\":\"PERCENT_OFF\",\"config\":{\"percent\":0}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields['config.percent']").exists());
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Flash\",\"type\":\"FLASH_SALE\",\"config\":{\"percent\":10}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.endsAt").exists());

        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Summer 20\",\"type\":\"PERCENT_OFF\",\"badgeText\":\"SALE\",\"config\":{\"percent\":20}}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        // the card and the product page show the sale price
        onHost(h, null, "GET", "/api/v1/shop/products", null).andExpect(jsonPath("$.data[0].offer.salePriceMinor").value(8000));
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slug(), null).andExpect(jsonPath("$.variants[0].salePriceMinor").value(8000));
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 1)).andExpect(jsonPath("$.discountMinor").value(2000));

        // buy 1 get 1 free on top of the sale price (the cheaper unit is free)
        String bxgy = ok(onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"1+1\",\"type\":\"BUY_X_GET_Y\",\"appliesTo\":\"PRODUCTS\",\"productIds\":[\"" + s.product() + "\"],\"config\":{\"buyQty\":1,\"getQty\":1}}")
                .andExpect(status().isCreated()));
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 2)).andExpect(jsonPath("$.discountMinor").value(4000 + 8000));
        onHost(h, a, "PUT", "/api/v1/store/offers/" + JsonPath.read(bxgy, "$.id") + "/active", "{\"active\":false}").andExpect(jsonPath("$.state").value("PAUSED"));

        // spend 15,000+ save 10%: on the sale prices (2 x 8,000 = 16,000)
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Spend\",\"type\":\"ORDER_THRESHOLD\",\"config\":{\"minSubtotalMinor\":15000,\"discountType\":\"PERCENT\",\"value\":10}}").andExpect(status().isCreated());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 2)).andExpect(jsonPath("$.discountMinor").value(4000 + 1600));
        // free delivery
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 1)).andExpect(jsonPath("$.shippingMinor").value(org.hamcrest.Matchers.greaterThan(0)));
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Free delivery\",\"type\":\"FREE_SHIPPING\",\"showBanner\":true,\"config\":{\"minSubtotalMinor\":0}}").andExpect(status().isCreated());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 1)).andExpect(jsonPath("$.shippingMinor").value(0)).andExpect(jsonPath("$.freeShipping").value(true));
        // a gift while it is in stock
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Gift\",\"type\":\"GIFT_WITH_PURCHASE\",\"config\":{\"minSubtotalMinor\":0,\"giftVariantId\":\"" + s.vM() + "\"}}").andExpect(status().isCreated());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 1)).andExpect(jsonPath("$.lines.length()").value(2)).andExpect(jsonPath("$.lines[1].gift").value(true));

        // the order keeps the offers, they count a use, and the orders screen filters by them and by date
        String order = ok(onHost(h, null, "POST", "/api/v1/shop/checkout", cart(s, s.vS(), 1)).andExpect(status().isCreated()));
        assertThat(jdbc.sql("SELECT jsonb_array_length(applied_offers) FROM commerce.orders WHERE id = :o").param("o", UUID.fromString(JsonPath.read(order, "$.orderId"))).query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).toString(), tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).plusDays(1).toString();
        onHost(h, a, "GET", "/api/v1/store/orders?discount=OFFER&from=" + today + "&to=" + today, null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, a, "GET", "/api/v1/store/orders?from=" + tomorrow, null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(h, a, "GET", "/api/v1/store/orders?status=REQUESTED,PREPARING&minTotal=1&sort=TOTAL_DESC&q=Tee", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, a, "GET", "/api/v1/store/offers?state=ACTIVE", null).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.name=='Summer 20')].usageCount").value(1));
        onHost(h, a, "GET", "/api/v1/store/offers/stats", null).andExpect(jsonPath("$.states.PAUSED").value(1));
        onHost(h, null, "GET", "/api/v1/shop/offers", null).andExpect(jsonPath("$[?(@.name=='Free delivery')]").exists());
    }

    @Test
    void bundleTiersAndFirstOrder() throws Exception {
        Shop s = shop();
        String h = s.t().host(), a = s.t().access();
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Tiers\",\"type\":\"TIERED_QTY\",\"stackable\":true,\"config\":{\"tiers\":[{\"minQty\":2,\"percent\":10},{\"minQty\":3,\"percent\":20}]}}").andExpect(status().isCreated());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 3)).andExpect(jsonPath("$.discountMinor").value(6000));
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Welcome\",\"type\":\"FIRST_ORDER\",\"stackable\":true,\"config\":{\"discountType\":\"FIXED\",\"value\":500}}").andExpect(status().isCreated());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, s.vS(), 3)).andExpect(jsonPath("$.discountMinor").value(6500));
        onHost(h, a, "POST", "/api/v1/store/offers", "{\"name\":\"Bundle\",\"type\":\"BUNDLE_PRICE\",\"productIds\":[\"" + s.product() + "\"],\"config\":{\"bundlePriceMinor\":100}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.productIds").exists());
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * prepare_order needs the item at the checkout turn, when the model no longer has the variant UUID (it came from a search tool result of an earlier turn, and the history the model gets
 * is text only). Found in the 2026-11-10 model comparison: about half of the first prepare_order calls of every model failed with «Invalid variantId» (the model invented
 * «شاحن-فولتا-20-وات-usb-c-أبيض»). The item is now identified by the product slug (which the prompt lists) and the options the customer chose, and the shop resolves the variant.
 */
class OrderVariantReferenceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    static final String DETAILS = "انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش. التوصيل امتى؟";

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Shop(Tenant t, String branch, String shirt, String shirtSlug, String mug, String mugSlug) {}

    String create(Tenant t, String json) throws Exception {
        return onHost(t.host(), t.access(), "POST", "/api/v1/store/products", json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    /** A shirt in two colours and two sizes (four variants) and a mug with one version. */
    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String shirt = "قميص قطن " + uniq();
        StringBuilder variants = new StringBuilder();
        for (String color : List.of("أسود", "أبيض")) for (String size : List.of("M", "L"))
            variants.append(variants.length() == 0 ? "" : ",").append("{\"sku\":\"SH-%s\",\"priceMinor\":15000,\"optionValues\":{\"اللون\":\"%s\",\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}".formatted(uniq(), color, size, branch));
        String made = create(t, "{\"name\":\"%s\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أسود\",\"أبيض\"]},{\"name\":\"المقاس\",\"values\":[\"M\",\"L\"]}],\"variants\":[%s]}".formatted(shirt, variants));
        String mug = "Mug " + uniq();
        String mugMade = create(t, "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"MG-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(mug, uniq(), branch));
        return new Shop(t, branch, shirt, JsonPath.read(made, "$.slug"), mug, JsonPath.read(mugMade, "$.slug"));
    }

    String send(Shop s, String conv, String text) throws Exception {
        return onHost(s.t().host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    void model(String text) { FakeAiProvider.SCRIPT.add(turns -> Turn.model(text)); }

    /** prepare_order arguments with the item as the test gives it (a map), and the customer's details. */
    Map<String, Object> order(Map<String, Object> item) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("items", List.of(item));
        a.putAll(Map.of("customerName", "كريم", "phone", "01023456789", "governorateCode", "CAI", "addressLine1", "شارع مكرم عبيد عمارة ٣ شقة ٧", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0));
        return a;
    }

    /** The option values of the variant in the conversation's pending summary («اللون=أسود|المقاس=L»), or null. */
    String pendingCombo(String conv) {
        return jdbc.sql("SELECT v.combo_key FROM chat.conversations c JOIN commerce.product_variants v ON v.id = (c.pending -> 'items' -> 0 ->> 'variantId')::uuid WHERE c.external_id = :c AND c.channel = 'WEB'")
                .param("c", conv).query(String.class).optional().orElse(null);
    }

    Object firstResultError() { return FakeAiProvider.RESULTS.isEmpty() ? "no tool result" : FakeAiProvider.RESULTS.get(0).result().get("error"); }

    @Test
    void aLaterTurnOrdersBySlugAndOptionsWithoutAnyVariantId() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم، ابعتلي الاسم والعنوان ورقم الموبايل");
        send(s, conv, "عايز " + s.shirt() + " أسود L");   // the variant ids of any search never reach the checkout turn
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", s.shirtSlug(), "options", Map.of("اللون", "أسود", "المقاس", "L"), "quantity", 1))));
        model("ده الملخص");
        send(s, conv, DETAILS);
        assertThat(firstResultError()).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
        assertThat(pendingCombo(conv)).isEqualTo("اللون=أسود|المقاس=L");
    }

    @Test
    void aProductWithOneVersionNeedsOnlyItsSlug() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم");
        send(s, conv, "عايز " + s.mug());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", s.mugSlug(), "quantity", 1))));
        model("ده الملخص");
        send(s, conv, DETAILS);
        assertThat(firstResultError()).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
        assertThat(pendingCombo(conv)).isNotNull();
    }

    @Test
    void theInventedSlugAndOptionIdOfTheComparisonIsNotNeededWhenTheSlugIsGiven() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم");
        send(s, conv, "عايز " + s.shirt() + " أبيض M");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("variantId", s.shirtSlug() + "-أبيض", "slug", s.shirtSlug(), "options", Map.of("اللون", "أبيض", "المقاس", "M"), "quantity", 1))));
        model("ده الملخص");
        send(s, conv, DETAILS);
        assertThat(firstResultError()).isNull();
        assertThat(pendingCombo(conv)).isEqualTo("اللون=أبيض|المقاس=M");
    }

    @Test
    void anAmbiguousOrUnknownOptionIsAnsweredWithTheRealChoicesNotWithAnIdDemand() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم");
        send(s, conv, "عايز " + s.shirt() + " أسود");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", s.shirtSlug(), "options", Map.of("اللون", "أسود"), "quantity", 1))));   // the size is open: two variants fit
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", s.shirtSlug(), "options", Map.of("اللون", "أخضر", "المقاس", "M"), "quantity", 1))));   // a colour the shirt does not have
        model("ممكن تقولي المقاس؟");
        send(s, conv, DETAILS);
        String first = String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error")), second = String.valueOf(FakeAiProvider.RESULTS.get(1).result().get("error"));
        assertThat(first).contains("المقاس").contains("M").contains("L").doesNotContain("Invalid variantId");
        assertThat(second).contains("أسود").contains("أبيض").doesNotContain("Invalid variantId");
        assertThat(pendingCombo(conv)).isNull();
    }

    @Test
    void theModelCannotPickAnOptionTheCustomerNeverSaid() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم");
        send(s, conv, "عايز " + s.shirt());   // no colour, no size said
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", s.shirtSlug(), "options", Map.of("اللون", "أسود", "المقاس", "L"), "quantity", 1))));
        model("تحب أنهي لون ومقاس؟");
        send(s, conv, DETAILS);
        assertThat(String.valueOf(firstResultError())).contains("has not chosen");
        assertThat(pendingCombo(conv)).isNull();
    }

    @Test
    void aSlugOfAnotherShopIsNeverResolved() throws Exception {
        Shop mine = shop(), other = shop();
        String conv = UUID.randomUUID().toString();
        model("تمام يا فندم");
        send(mine, conv, "عايز " + mine.mug());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(Map.of("slug", other.mugSlug(), "quantity", 1))));
        model("معلش حصل لبس");
        send(mine, conv, DETAILS);
        assertThat(firstResultError()).isNotNull();   // the other shop's slug is unknown here
        // the summary the code-side recovery may make is for the product the customer named, in THIS shop
        assertThat(jdbc.sql("SELECT count(*) FROM chat.conversations c JOIN commerce.product_variants v ON v.id = (c.pending -> 'items' -> 0 ->> 'variantId')::uuid JOIN commerce.products p ON p.id = v.product_id WHERE c.external_id = :c AND c.channel = 'WEB' AND p.slug = :o")
                .param("c", conv).param("o", other.mugSlug()).query(Long.class).single()).isZero();
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiEvalService;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The model comparison's order scenarios: the order a customer asked for is checked by code (variant, quantity, contact details, payment, placed once after the confirmation), and the reply records are kept. */
class AiEvalOrderCheckIntegrationTest extends IntegrationTestBase {
    @Autowired AiEvalService eval;
    @Autowired JdbcClient jdbc;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    /** A shop with one 100.00 mug (sku given) in stock; returns its variant id. */
    String product(Tenant t, String sku) throws Exception {
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        return JsonPath.read(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), sku, branch))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.variants[0].id");
    }

    JsonNode scenario(String sku, int qty, String payment) throws Exception {
        return JSON.readTree("""
                {"id": 990, "category": "order_flow", "customer": ["عايز كوب مج الأخضر TURNONE", "اسمي كريم سمير، 01012345678، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، كاش TURNTWO", "تمام"],
                 "expect": ["shows the summary"], "order": {"sku": "%s", "qty": %d, "name": "كريم", "phone": "01012345678", "addressContains": ["مكرم"], "payment": "%s"}}
                """.formatted(sku, qty, payment));
    }

    Map<String, Object> order(String variant, int qty) {
        return Map.of("items", List.of(Map.of("variantId", variant, "quantity", qty)), "customerName", "كريم سمير", "phone", "01012345678", "governorateCode", "CAI",
                "addressLine1", "شارع مكرم عبيد عمارة ٣ شقة ٧", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void anOrderPlacedRightIsCheckedByCodeAndTheReplyRecordsCarryTimeTokensAndTools() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String sku = "ORD-" + uniq();
        String v = product(t, sku);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNONE", turns -> Turn.model("متاح يا فندم، ابعتلي بياناتك")));
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNTWO", turns -> FakeAiProvider.call("prepare_order", order(v, 1))));
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNTWO", turns -> Turn.model("ده الملخص")));
        Map<String, Object> r = eval.play(tenant, "default", UUID.randomUUID(), scenario(sku, 1, "CASH_ON_DELIVERY"), false, 0.3);
        Map<String, Object> ck = (Map<String, Object>) r.get("orderCheck");
        assertThat(ck).as(String.valueOf(ck)).containsEntry("summaryShown", true).containsEntry("itemAndQuantityCorrect", true).containsEntry("contactDetailsCorrect", true)
                .containsEntry("paymentCorrect", true).containsEntry("placedOnce", true).containsEntry("placedAfterSummary", true).containsEntry("totalNotBelowGoods", true).containsEntry("allCorrect", true);
        // one record per customer message: the last one ("تمام") places the order by code, with no model call
        List<Map<String, Object>> turns = (List<Map<String, Object>>) r.get("turns");
        assertThat(turns).hasSize(3);
        assertThat(turns.get(2)).containsEntry("path", "CODE");
        assertThat(turns).allSatisfy(x -> assertThat((Number) x.get("ms")).isNotNull());
        // the tool the model called is recorded, with whether it worked
        List<Map<String, Object>> tools = (List<Map<String, Object>>) r.get("toolCalls");
        assertThat(tools).extracting(x -> x.get("tool")).contains("prepare_order");
        assertThat(tools).filteredOn(x -> "prepare_order".equals(x.get("tool"))).allSatisfy(x -> assertThat(x.get("ok")).isEqualTo(true));
        assertThat((Map<String, Object>) r.get("usage")).containsKey("toolsHash").containsKey("systemChars");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aWrongQuantityOrANeverPlacedOrderFailsTheCodeCheck() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String sku = "ORD-" + uniq();
        String v = product(t, sku);
        // the customer asked for two, the model prepares one: the summary has the wrong quantity
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNONE", turns -> Turn.model("متاح يا فندم، ابعتلي بياناتك")));
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNTWO", turns -> FakeAiProvider.call("prepare_order", order(v, 1))));
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("TURNTWO", turns -> Turn.model("ده الملخص")));
        Map<String, Object> r = eval.play(tenant, "default", UUID.randomUUID(), scenario(sku, 2, "CASH_ON_DELIVERY"), false, null);
        Map<String, Object> ck = (Map<String, Object>) r.get("orderCheck");
        assertThat(ck).containsEntry("itemAndQuantityCorrect", false).containsEntry("allCorrect", false);
        assertThat(ck).containsEntry("contactDetailsCorrect", true);
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * I-010 live (tech-zone, three separate «عايز X» requests, then the details): the summary held only the headset. The catalog here has the 80 real product names of the shop, so the products
 * are matched against names that share words (several cases, several headsets), exactly as live.
 */
class MultiItemRealNamesIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void threeSeparateRequestsWithPartialNamesAreAllInTheSummary() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        List<String> names = new ObjectMapper().readValue(getClass().getResourceAsStream("/techzone-product-names.json"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        Map<String, String> variant = new LinkedHashMap<>();
        for (String n : names) {
            String made = onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                    "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"R-%s\",\"priceMinor\":30000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(n, uniq(), branch))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            variant.put(n, JsonPath.read(made, "$.variants[0].id"));
        }
        String flash = "فلاشة زينترا USB 3.2", headset = "سماعة جيمنج سونيك برو", cover = "جراب جلد قلاب نوفا X12";
        String ext = "real-" + uniq();
        for (String msg : List.of("عايز فلاشة زينترا USB أسود 128 جيجا", "عايز سماعة جيمنج سونيك أسود", "عايز جراب جلد قلاب أسود")) {
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
            chat.reply(tenant, "WEB", ext, null, msg);
        }
        Map<String, Object> order = new LinkedHashMap<>(Map.of("customerName", "سيم", "phone", "01023456789", "governorateCode", "CAI", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0));
        order.put("items", List.of(Map.of("variantId", variant.get(headset), "quantity", 1)));   // the live model's order: the headset only
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        var last = chat.reply(tenant, "WEB", ext, null, "انا سيم تيست، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش");
        // the summary (made by code or by the model) holds all three requested products, priced by the backend (3 x 300)
        assertThat(last.text()).as(last.text()).contains(flash).contains(headset).contains(cover).contains("900");
    }
}

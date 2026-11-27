package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** «تمام» to a summary whose stock changed meanwhile (another buyer took it): the customer hears which item is short, how many are left and what else there is, in their words. */
class OrderFailureTextIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Cap(UUID tenant, String black) {}

    Cap shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String created = onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"كاب قطن","options":[{"name":"اللون","values":["أسود","أبيض","كحلي"]}],
                 "variants":[{"sku":"CB-%1$s","priceMinor":18000,"optionValues":{"اللون":"أسود"},"stock":[{"branchId":"%2$s","quantity":5}]},
                             {"sku":"CW-%1$s","priceMinor":18000,"optionValues":{"اللون":"أبيض"},"stock":[{"branchId":"%2$s","quantity":3}]},
                             {"sku":"CN-%1$s","priceMinor":18000,"optionValues":{"اللون":"كحلي"},"stock":[{"branchId":"%2$s","quantity":4}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Cap(id, JsonPath.read(created, "$.variants[0].id"));
    }

    /** A summary for {@code qty} black caps, then the black stock becomes {@code left}, then «تمام». */
    String confirmAfterStockChange(Cap c, int qty, int left) {
        String ext = "fail-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", Map.of("items", List.of(Map.of("variantId", c.black(), "quantity", qty)), "customerName", "Nada Ali",
                "phone", "01011112222", "governorateCode", "CAI", "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY")));
        String summary = chat.reply(c.tenant(), "WEB", ext, null, "عايز " + (qty == 2 ? "٢" : "١") + " كاب قطن أسود، هيوصل امتى؟").text();
        assertThat(summary).contains("ملخص");
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = :n WHERE variant_id = CAST(:v AS uuid)").param("n", left).param("v", c.black()).update();   // another buyer took them
        return chat.reply(c.tenant(), "WEB", ext, null, "تمام").text();
    }

    @Test
    void theCustomerHearsWhichItemIsShortHowManyAreLeftAndWhatElseThereIs() throws Exception {
        Cap c = shop();
        String r = confirmAfterStockChange(c, 2, 1);
        assertThat(r).contains("كاب قطن").contains("أسود").contains("فاضل منه قطعة واحدة بس").contains("طالب ٢")
                .contains("أبيض").contains("كحلي").contains("تحب ناخد الكمية المتاحة بس").doesNotContain("Not enough").doesNotContain("stock");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t").param("t", c.tenant()).query(Long.class).single()).isZero();
        // sold out
        String gone = confirmAfterStockChange(c, 1, 0);
        assertThat(gone).contains("خلص للأسف").contains("أبيض").doesNotContain("Not enough");
    }
}

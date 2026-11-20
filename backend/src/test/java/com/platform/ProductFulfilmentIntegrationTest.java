package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** How a business hands over products (delivery only, pickup only, both) is its own setting, separate from appointments, and the assistant, checkout and shop data all follow it. */
class ProductFulfilmentIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    UUID tid(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    void shop(Tenant t, String name) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"WOMEN\",\"name\":\"%s\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أسود\"]}],\"variants\":[{\"sku\":\"S-%s\",\"priceMinor\":50000,\"optionValues\":{\"اللون\":\"أسود\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}]}".formatted(name, uniq(), branch)).andExpect(status().isCreated());
    }

    String mode(Tenant t) throws Exception { return JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/fulfilment", null).andExpect(status().isOk())), "$.mode"); }
    ResultActions put(Tenant t, String mode) throws Exception { return onHost(t.host(), t.access(), "PUT", "/api/v1/store/fulfilment", "{\"mode\":\"%s\"}".formatted(mode)); }

    @Test
    void theOwnerChoosesDeliveryPickupOrBothAndTheShippingMethodsFollow() throws Exception {
        Tenant t = onboard("STORE");   // a new shop starts with pickup and a standard delivery
        assertThat(mode(t)).isEqualTo("BOTH");
        put(t, "PICKUP").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADD_PICKUP_BRANCH_FIRST"));   // collecting needs a place to collect
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/branches/" + branch, "{\"isPickup\":true}").andExpect(status().isOk());
        put(t, "PICKUP").andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("PICKUP"));
        assertThat(JsonPath.<List<String>>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping-methods", null)), "$[?(@.isActive==true)].type")).containsExactly("PICKUP");
        // the public checkout offers only what the owner chose
        assertThat(JsonPath.<List<String>>read(body(onHost(t.host(), null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[*].type")).containsExactly("PICKUP");
        put(t, "DELIVERY").andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("DELIVERY"));
        assertThat(JsonPath.<List<String>>read(body(onHost(t.host(), null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[*].type")).doesNotContain("PICKUP").isNotEmpty();
        put(t, "BOTH").andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("BOTH"));
        put(t, "TELEPORT").andExpect(status().isBadRequest());
        // another business is not touched
        Tenant other = onboard("STORE");
        assertThat(mode(other)).isEqualTo("BOTH");
    }

    @Test
    void aShopWithNoDeliveryMethodCannotChooseDeliveryOnly() throws Exception {
        Tenant t = onboard("STORE");
        for (var m : JsonPath.<List<Map<String, Object>>>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping-methods", null)), "$")) if (!"PICKUP".equals(m.get("type")))
            onHost(t.host(), t.access(), "DELETE", "/api/v1/store/shipping-methods/" + m.get("id"), null).andExpect(status().isOk());
        put(t, "DELIVERY").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADD_DELIVERY_FIRST"));
    }

    @Test
    void theAssistantNeverPromisesDeliveryToAPickupOnlyShopAndNeverPreparesADeliveryOrder() throws Exception {
        Tenant t = onboard("STORE");
        shop(t, "شنطة يد " + uniq());
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/branches/" + branch, "{\"isPickup\":true}").andExpect(status().isOk());
        put(t, "PICKUP").andExpect(status().isOk());
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عندكم شنط؟\"}".formatted(conv)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("PICKUP ONLY").contains("does NOT deliver");
        // a delivery order is refused by the tool itself, so no summary and no order exist
        String slug = jdbc.sql("SELECT slug FROM commerce.products WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1").param("t", tid(t)).query(String.class).single();
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", Map.of("items", List.of(Map.of("slug", slug, "options", Map.of("اللون", "أسود"), "quantity", 1)), "customerName", "Ali", "phone", "01012345678", "governorateCode", "CAIRO", "addressLine1", "5 Tahrir St", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز واحدة وتوصلولي\"}".formatted(conv)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t").param("t", tid(t)).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND m.content LIKE '%does NOT deliver%'").param("t", tid(t)).query(Long.class).single()).isGreaterThanOrEqualTo(0L);
        // the same shop in DELIVERY mode: the prompt says no pickup
        put(t, "DELIVERY").andExpect(status().isOk());
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عندكم شنط؟\"}".formatted(UUID.randomUUID())).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("DELIVERY ONLY").contains("never tell a customer they can collect");
    }
}

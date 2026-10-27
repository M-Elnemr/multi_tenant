package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** A customer who adds an item after ordering gets ONE changed order, never a second one; a "yes" places only the summary it answers; the customer is addressed as they said. */
class AiOrderChangeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String product(Tenant t, String name) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"%s %s\",\"variants\":[{\"sku\":\"S-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
    }

    static Map<String, Object> prepare(List<String> variants, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("items", variants.stream().map(v -> Map.of("variantId", v, "quantity", 1)).toList(), "customerName", "Mido Ali", "phone", "01011112222",
                "governorateCode", "GIZ", "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY"));
        m.putAll(extra);
        return m;
    }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    List<Map<String, Object>> orders(UUID tenant) {
        return jdbc.sql("SELECT order_number, status, (SELECT count(*) FROM commerce.order_items i WHERE i.order_id = o.id) AS items FROM commerce.orders o WHERE tenant_id = :t ORDER BY created_at").param("t", tenant).query().listOfRows();
    }

    @Test
    void addingAnItemAfterOrderingChangesTheSameOrderInsteadOfPlacingASecondOne() throws Exception {
        Tenant t = onboard("STORE");
        String shirt = product(t, "Shirt"), pants = product(t, "Pants");
        UUID tenant = id(t);
        String ext = "change-" + uniq();

        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(shirt), Map.of())));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(tenant, "WEB", ext, null, "احجزلي القميص");
        String first = chat.reply(tenant, "WEB", ext, null, "تمام").order().get("orderNumber").toString();

        // a new order while the first one can still be changed is refused; a change that drops the shirt is refused; the full list replaces the order
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(shirt, pants), Map.of())));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(pants), Map.of("changeOrder", first))));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(shirt, pants), Map.of("changeOrder", first))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        String summary = chat.reply(tenant, "WEB", ext, null, "وضيف البنطلون مع القميص").text();
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains(first).contains("changeOrder");
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error").toString()).contains("Shirt").contains("leaves out");
        assertThat(summary).contains("بعد التعديل").contains(first).contains("Shirt").contains("Pants");

        var done = chat.reply(tenant, "WEB", ext, null, "تمام");
        String second = done.order().get("orderNumber").toString();
        assertThat(done.text()).contains("تم تعديل الأوردر").contains(second).contains(first);
        var all = orders(tenant);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("status")).isEqualTo("CANCELLED");
        assertThat(all.get(1).get("status")).isEqualTo("REQUESTED");
        assertThat(((Number) all.get(1).get("items")).intValue()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT reason FROM commerce.order_status_history h JOIN commerce.orders o ON o.id = h.order_id WHERE o.tenant_id = :t AND o.order_number = :n AND h.new_status = 'CANCELLED'").param("t", tenant).param("n", first).query(String.class).single())
                .contains("replaced by order " + second);
        // the replaced order is not counted against the customer as a cancellation
        assertThat(jdbc.sql("SELECT coalesce(sum(cancelled_count), 0) FROM commerce.phone_flags WHERE tenant_id = :t").param("t", tenant).query(Long.class).single()).isZero();
    }

    @Test
    void aYesAfterOtherMessagesNeverPlacesAnOutOfDateSummary() throws Exception {
        Tenant t = onboard("STORE");
        String shirt = product(t, "Shirt");
        UUID tenant = id(t);
        String ext = "stale-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(shirt), Map.of())));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(tenant, "WEB", ext, null, "احجز القميص");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("حاضر يا فندم، هجمعلك الاتنين في أوردر واحد"));
        chat.reply(tenant, "WEB", ext, null, "وفين البنطلون؟");

        // the "yes" goes to the model (not placed in code), and placing the old summary is refused
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("هبعتلك الملخص الجديد"));
        var r = chat.reply(tenant, "WEB", ext, null, "تمام");
        assertThat(r.order()).isNull();
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("latest summary");
        assertThat(orders(tenant)).isEmpty();
    }

    @Test
    void theCustomerIsAddressedAsTheySaidAndNeverByTheBareName() {
        UUID tenant;
        try { tenant = id(onboard("STORE")); } catch (Exception e) { throw new IllegalStateException(e); }
        String ext = "gender-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("آسف يا أستاذ ميدو"));
        chat.reply(tenant, "WEB", ext, null, "انا راجل مش بنت", "ميدو");
        assertThat(FakeAiProvider.lastSystem).contains("MAN (they said so)").contains("«أستاذ ميدو»").contains("NEVER the bare name («يا ميدو»");

        // nothing said and no known name: how they speak of themselves («عاوز») tells
        String other = "words-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        chat.reply(tenant, "WEB", other, null, "انا عاوز بنطلون");
        assertThat(FakeAiProvider.lastSystem).contains("MAN (how they speak of themselves)");
    }
}

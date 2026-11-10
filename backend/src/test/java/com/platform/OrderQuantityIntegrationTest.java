package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * A quantity the customer stated is the quantity of the order summary, however many messages earlier they said it, and nothing (code or model) silently changes it.
 * Found in the 2026-11-10 model comparison: «عايز اتنين ماوس باد» ... later messages ... summary with × 1 (6 of 12 two-item plays, every model).
 */
class OrderQuantityIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    static final String DETAILS = "انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧ الدور ٢، ٠١٠٢٣٤٥٦٧٨٩، كاش";
    static final String ASK_DETAILS = "متاح عندنا يا فندم. ابعتلي الاسم والعنوان ورقم الموبايل.";

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Shop(Tenant t, String name, String variant) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "Mug " + uniq();
        String variant = JsonPath.read(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":50}]}]}".formatted(name, uniq(), branch))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.variants[0].id");
        return new Shop(t, name, variant);
    }

    String send(Shop s, String conv, String text) throws Exception {
        return onHost(s.t().host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The quantity in the conversation's pending summary, or null when there is none. */
    Integer pendingQty(String conv) {
        return jdbc.sql("SELECT (pending -> 'items' -> 0 ->> 'quantity')::int FROM chat.conversations WHERE external_id = :c AND channel = 'WEB'").param("c", conv).query(Integer.class).optional().orElse(null);
    }

    Map<String, Object> prepare(Shop s, int qty) {
        return Map.of("items", List.of(Map.of("variantId", s.variant(), "quantity", qty)), "customerName", "كريم", "phone", "01023456789", "governorateCode", "CAI",
                "addressLine1", "شارع مكرم عبيد عمارة ٣ شقة ٧", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
    }

    /** The reply the scripted model gives when it is only asked something (and the same text for any other turn that reaches it). */
    void model(String text) { FakeAiProvider.SCRIPT.add(turns -> Turn.model(text)); }

    // ---- the code writes the summary (details complete, no question): quantity from what the customer said, whenever ----------------------

    @Test
    void theSummaryMadeByCodeKeepsTheQuantityTheCustomerAskedForOneToFour() throws Exception {
        Shop s = shop();
        String[][] phrases = {{"", "1"}, {"اتنين", "2"}, {"٣", "3"}, {"اربعة", "4"}, {"4", "4"}, {"تلاتة", "3"}};
        for (String[] p : phrases) {
            String conv = UUID.randomUUID().toString();
            model(ASK_DETAILS);
            send(s, conv, ("عايز " + p[0] + " " + s.name()).replace("  ", " "));
            send(s, conv, DETAILS);   // no question: code makes the summary before any model call
            assertThat(pendingQty(conv)).as("«%s»", p[0]).isEqualTo(Integer.parseInt(p[1]));
        }
    }

    @Test
    void aQuantitySaidSeveralMessagesBeforeCheckoutIsKept() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز اتنين " + s.name());
        model("الشحن بيختلف على حسب المحافظة يا فندم");
        send(s, conv, "الشحن بكام؟");
        model("طبعًا، الدفع كاش عند الاستلام متاح");
        send(s, conv, "ينفع كاش؟");
        send(s, conv, DETAILS);
        assertThat(pendingQty(conv)).isEqualTo(2);
    }

    @Test
    void theNumbersOfTheAddressAndThePhoneAreNeverTheQuantity() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز " + s.name());
        send(s, conv, DETAILS);   // «عمارة ٣ شقة ٧ الدور ٢» and the mobile hold the digits 3, 7, 2
        assertThat(pendingQty(conv)).isEqualTo(1);
    }

    @Test
    void theLatestThingTheCustomerSaidWinsWhenTheyChangeTheQuantityBeforeTheSummary() throws Exception {
        Shop s = shop();
        String up = UUID.randomUUID().toString(), down = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, up, "عايز اتنين " + s.name());
        model("تمام يا فندم");
        send(s, up, "لا خليهم ٣");
        send(s, up, DETAILS);
        assertThat(pendingQty(up)).isEqualTo(3);
        model(ASK_DETAILS);
        send(s, down, "عايز اتنين " + s.name());
        model("تمام يا فندم");
        send(s, down, "لا واحد بس");
        send(s, down, DETAILS);
        assertThat(pendingQty(down)).isEqualTo(1);
    }

    // ---- the model writes the summary: it cannot lower a quantity the customer stated, and cannot raise one they never said --------------

    @SuppressWarnings("unchecked")
    @Test
    void aModelThatDropsTheStatedQuantityIsRefusedAndTheSummaryKeepsTheQuantity() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز اتنين " + s.name());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(s, 1)));   // the model "forgets" the two
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(s, 2)));   // after the refusal it uses the customer's number
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        send(s, conv, DETAILS + ". التوصيل امتى؟");   // a question: the model's turn
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNotNull();
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).contains("asked for 2");
        assertThat(pendingQty(conv)).isEqualTo(2);
    }

    @Test
    void aModelCannotRaiseAQuantityTheCustomerNeverSaid() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز " + s.name());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(s, 2)));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(s, 1)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        send(s, conv, DETAILS + ". التوصيل امتى؟");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).contains("never asked for 2");
        assertThat(pendingQty(conv)).isEqualTo(1);
    }

    @Test
    void aModelMayUseTheNewNumberWhenTheCustomerChangedTheirMind() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز اتنين " + s.name());
        model("تمام يا فندم");
        send(s, conv, "لا خليهم ٣");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(s, 3)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        send(s, conv, DETAILS + ". التوصيل امتى؟");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).isNull();
        assertThat(pendingQty(conv)).isEqualTo(3);
    }

    @Test
    void theConfirmedSummaryAndThePlacedOrderCarryTheQuantity() throws Exception {
        Shop s = shop();
        String conv = UUID.randomUUID().toString();
        model(ASK_DETAILS);
        send(s, conv, "عايز اتنين " + s.name());
        String summary = JsonPath.read(send(s, conv, DETAILS), "$.reply");
        assertThat(summary).contains("× 2");
        String confirmed = send(s, conv, "تمام");
        String number = JsonPath.read(confirmed, "$.order.orderNumber");
        assertThat(jdbc.sql("SELECT sum(oi.quantity) FROM commerce.order_items oi JOIN commerce.orders o ON o.id = oi.order_id JOIN core.tenants t ON t.id = o.tenant_id WHERE o.order_number = :n AND t.slug = :s").param("n", number).param("s", s.t().slug()).query(Long.class).single()).isEqualTo(2L);
    }
}

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
import org.springframework.test.web.servlet.ResultActions;

/** «Payment received» against a REAL placed order: cut while the order is unpaid, allowed once it is really paid; and the order block of the prompt shows the payment status. */
class PaymentClaimIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String send(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    @Test
    void aPaymentClaimIsCutWhileTheOrderIsUnpaidAndAllowedOnceTheOrderIsReallyPaid() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String variant = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String conv = UUID.randomUUID().toString();
        Map<String, Object> prep = Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "customerName", "Nada Ali", "phone", "01011112222", "governorateCode", "CAI",
                "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prep));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Total shown. Confirm?"));
        send(t, conv, "I want the mug");
        String placed = send(t, conv, "تمام حضرتك");   // a plain agreement places the order in code
        assertThat(JsonPath.<String>read(placed, "$.order.orderNumber")).isNotBlank();
        assertThat(jdbc.sql("SELECT payment_status FROM commerce.orders ORDER BY created_at DESC LIMIT 1").query(String.class).single()).isEqualTo("UNPAID");

        // unpaid: the model's claim never reaches the customer, and the prompt shows the real status
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("وصلتكم الفلوس", turns -> Turn.model("تم استلام الدفع يا فندم")));
        String r1 = send(t, conv, "وصلتكم الفلوس؟");
        assertThat(JsonPath.<String>read(r1, "$.reply")).doesNotContain("تم استلام الدفع");
        assertThat(FakeAiProvider.lastSystem).contains("ORDER ON FILE (this customer's latest order").contains("payment status UNPAID");

        // really paid (what the payment provider's confirmation does): the same words are now true and are allowed
        jdbc.sql("UPDATE commerce.orders SET payment_status = 'PAID' WHERE id = (SELECT id FROM commerce.orders ORDER BY created_at DESC LIMIT 1)").update();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("طيب والدفع", turns -> Turn.model("تم استلام الدفع يا فندم، شكرا ليك")));
        String r2 = send(t, conv, "طيب والدفع؟");
        assertThat(JsonPath.<String>read(r2, "$.reply")).contains("تم استلام الدفع");
        assertThat(FakeAiProvider.lastSystem).contains("payment status PAID");
    }
}

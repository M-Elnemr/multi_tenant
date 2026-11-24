package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The admin's «AI optimization» switch: off sends the request exactly as before (everything in the system message); on sends the fixed part as the system message and this reply's
 * context (the guest note, the products of the chat with their exact stock) in front of the customer's newest message, with no stock counts in the cached catalog.
 */
class OptimizedLayoutIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @AfterEach
    void classicAgain() { switchTo(null); }

    void switchTo(String v) {
        jdbc.sql("INSERT INTO chat.platform_ai_config (id, optimization) VALUES (1, :v) ON CONFLICT (id) DO UPDATE SET optimization = :v").param("v", v).update();
        com.platform.chat.AiConfigTestAccess.forget();
    }

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String system = "";

    List<List<Turn>> ask(Tenant t, String message) throws Exception {
        List<List<Turn>> seen = new ArrayList<>();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { seen.add(List.copyOf(turns)); system = FakeAiProvider.lastSystem; return Turn.model("عندنا قميص كتان أبيض بـ 450 جنيه، تحب مقاس إيه؟"); });
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(UUID.randomUUID(), message)).andExpect(status().isOk());
        return seen;
    }

    Tenant shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"قميص كتان أبيض","variants":[{"sku":"L-%s","priceMinor":45000,"stock":[{"branchId":"%s","quantity":7}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated());
        return t;
    }

    @Test
    void offSendsEverythingInTheSystemMessageAsBefore() throws Exception {
        Tenant t = shop();
        switchTo("off");
        var seen = ask(t, "قميص كتان أبيض متاح؟");
        assertThat(system).contains("The customer is a guest").contains("7 in stock").doesNotContain("⟦");
        var last = seen.get(0).get(seen.get(0).size() - 1);
        assertThat(last.text()).isEqualTo("قميص كتان أبيض متاح؟");
    }

    @Test
    void onSendsTheFixedPartAsSystemAndTheContextWithTheNewestMessage() throws Exception {
        Tenant t = shop();
        switchTo("on");
        var seen = ask(t, "قميص كتان أبيض متاح؟");
        assertThat(system).doesNotContain("The customer is a guest").doesNotContain("7 in stock").contains("WHERE THIS REPLY'S CONTEXT IS").contains("قميص كتان أبيض");
        var last = seen.get(0).get(seen.get(0).size() - 1);
        assertThat(last.text()).startsWith("⟦CONTEXT FOR THIS REPLY").contains("The customer is a guest").contains("PRODUCTS FOR THE CUSTOMER'S NEWEST MESSAGE").contains("7 in stock")
                .endsWith("قميص كتان أبيض متاح؟");
    }
}

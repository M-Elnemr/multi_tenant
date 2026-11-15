package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider;
import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChannelAi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole chat pipeline with a primary model that fails and a counted fallback model: a fallback must never repeat what the failed attempt already did (an order),
 * and must answer when nothing had been done yet. The two models are scripted stand-ins, so this proves the pipeline's behaviour, not any real provider.
 */
@Import(ProviderFallbackOrderIntegrationTest.Config.class)
class ProviderFallbackOrderIntegrationTest extends IntegrationTestBase {
    static final AtomicInteger FALLBACK_CALLS = new AtomicInteger();
    static volatile String fallbackSaid = "جاوب البديل";

    @TestConfiguration
    static class Config {
        @Bean @Primary
        ChannelAi channelAi(FakeAiProvider fake) {
            AiProvider alibaba = new AiProvider() {
                @Override public String code() { return "qwen"; }
                @Override public String label() { return "Alibaba Qwen (test)"; }
                @Override public boolean available() { return true; }
                @Override public Turn next(String system, List<Turn> turns, List<ToolDef> tools) { FALLBACK_CALLS.incrementAndGet(); return Turn.model(fallbackSaid); }
            };
            return new ChannelAi(fake, fake, fake, alibaba, "default", "default");
        }
    }

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FALLBACK_CALLS.set(0); fallbackSaid = "جاوب البديل"; }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String mug(Tenant t) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
    }

    Map<String, Object> prepare(String variant) {
        return Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "customerName", "Nada Ali", "phone", "01011112222", "governorateCode", "CAI",
                "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
    }

    String send(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    long orders(Tenant t) throws Exception { return ((Number) JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/orders", null)), "$.meta.total")).longValue(); }

    static Turn fail() { throw new AiUnavailableException("SERVER_ERROR", "HTTP 503", null); }

    @Test
    void primaryFailsBeforeDoingAnythingTheFallbackAnswersAndTheCustomerSeesNoProviderError() throws Exception {
        Tenant t = onboard("STORE");
        mug(t);
        FakeAiProvider.SCRIPT.add(turns -> fail());
        String reply = JsonPath.read(send(t, UUID.randomUUID().toString(), "عايز كوباية"), "$.reply");
        assertThat(reply).isEqualTo("جاوب البديل");
        assertThat(FALLBACK_CALLS.get()).isEqualTo(1);
    }

    @Test
    void primaryFailsAfterAReadOnlyToolTheWholeReplyRestartsOnTheFallback() throws Exception {
        Tenant t = onboard("STORE");
        mug(t);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "mug")));
        FakeAiProvider.SCRIPT.add(turns -> fail());
        String reply = JsonPath.read(send(t, UUID.randomUUID().toString(), "عايز كوباية"), "$.reply");
        assertThat(reply).isEqualTo("جاوب البديل");
        assertThat(FALLBACK_CALLS.get()).isEqualTo(1);
    }

    @Test
    void primaryPlacesTheOrderThenFailsTheFallbackIsNotAskedToRepeatItAndNoSecondOrderExists() throws Exception {
        Tenant t = onboard("STORE");
        String variant = mug(t);
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(variant)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Total 150. Confirm?"));
        send(t, conv, "ok I want the mug");
        assertThat(orders(t)).isZero();
        FakeAiProvider.reset();
        // a message that is not a bare «yes» (so the model, not the code shortcut, handles it): the model places the order, then its next call fails
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> fail());
        String reply = send(t, conv, "تمام بس هيوصل امتى؟ كمل الأوردر");
        assertThat(orders(t)).as("exactly one order").isEqualTo(1);
        assertThat(FALLBACK_CALLS.get()).as("the fallback must not be asked to act again").isZero();
        assertThat(JsonPath.<String>read(reply, "$.reply")).doesNotContain("HTTP 503").doesNotContain("جاوب البديل");
        assertThat(JsonPath.<String>read(reply, "$.order.orderNumber")).isNotBlank();
        // and a replay of the confirmation still cannot make a second one
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("place_order", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("already done"));
        send(t, conv, "yes");
        assertThat(orders(t)).isEqualTo(1);
    }
}

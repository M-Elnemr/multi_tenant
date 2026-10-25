package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.AiProvider.Usage;
import com.platform.chat.AiUsageService;
import com.platform.chat.ChatService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** While two models are compared, the test word "ai-name" names the model that answers a channel (no model call), and the admin screens cost each model with its own prices. */
@TestPropertySource(properties = "app.ai.name-probe=true")
class ModelProbeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired AiUsageService usage;

    /** One field of one model's row in the by-model list. */
    static Number one(String json, String model, String field) {
        return JsonPath.<List<Number>>read(json, "$.byModel[?(@.model=='" + model + "')]." + field).get(0);
    }

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void aiNameAnswersWithTheModelWithoutCallingIt() throws Exception {
        Tenant shop = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        var reply = chat.reply(id, "WEB", "probe-visitor-1", null, "ai-name");
        assertThat(reply.text()).isEqualTo("FakeAiProvider");   // the default model of the tests names itself by its class
        assertThat(chat.reply(id, "WHATSAPP", "201000000001", null, "AI name").text()).isEqualTo("FakeAiProvider");
        assertThat(FakeAiProvider.lastSystem).isEmpty();   // the model was never asked
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id = :t AND instant").param("t", id).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void eachModelIsCostedWithItsOwnPrices() throws Exception {
        Tenant admin = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", admin.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String token = JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(admin.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
        Tenant shop = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        // one million input + one million output tokens on each model
        usage.record(id, "CHAT", "WHATSAPP", UUID.randomUUID(), false, "claude", List.of(new AiUsageService.Round(List.of(), new Usage(1_000_000, 1_000_000, 0))), "anthropic", false);
        usage.record(id, "CHAT", "WEB", UUID.randomUUID(), false, "gemini", List.of(new AiUsageService.Round(List.of(), new Usage(1_000_000, 1_000_000, 0))), "gemini", false);
        usage.record(id, "CHAT", "WHATSAPP", UUID.randomUUID(), false, "fell back", List.of(new AiUsageService.Round(List.of(), new Usage(10, 1, 0))), "gemini", true);

        String today = LocalDate.now(ZoneId.of("Africa/Cairo")).toString();
        String s = onHost("platform.test", token, "GET", "/api/v1/platform/ai-usage/shops/" + id + "?day=" + today, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(one(s, "anthropic", "costUsd").doubleValue()).isEqualTo(0.60);   // 0.10 in + 0.50 out
        assertThat(one(s, "gemini", "costUsd").doubleValue()).isGreaterThan(1.7);    // 0.25 in + 1.50 out
        assertThat(one(s, "gemini", "fallbacks").intValue()).isEqualTo(1);
        assertThat(JsonPath.<Number>read(s, "$.totals.costUsd").doubleValue()).isGreaterThan(2.3);

        // choosing one model counts only that model's requests everywhere on the screen; the by-model comparison keeps both
        String claude = onHost("platform.test", token, "GET", "/api/v1/platform/ai-usage/shops/" + id + "?day=" + today + "&model=anthropic", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(claude, "$.totals.input").longValue()).isEqualTo(1_000_000L);
        assertThat(JsonPath.<Number>read(claude, "$.totals.costUsd").doubleValue()).isEqualTo(0.60);
        assertThat(JsonPath.<List<Object>>read(claude, "$.conversations")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(claude, "$.byModel")).hasSize(2);
        String gem = onHost("platform.test", token, "GET", "/api/v1/platform/ai-usage?day=" + today + "&model=gemini", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(gem, "$.model")).isEqualTo("gemini");
        assertThat(JsonPath.<List<String>>read(gem, "$.byModel[*].model")).contains("anthropic", "gemini");
        assertThat(JsonPath.<Number>read(gem, "$.totals.input").longValue()).isGreaterThanOrEqualTo(1_000_010L);
        assertThat(JsonPath.<String>read(onHost("platform.test", token, "GET", "/api/v1/platform/ai-usage?day=" + today + "&model=all", null).andReturn().getResponse().getContentAsString(), "$.model")).isEqualTo("all");
        onHost("platform.test", token, "GET", "/api/v1/platform/ai-usage?day=" + today + "&model=x';drop", null).andExpect(status().isBadRequest());
    }
}

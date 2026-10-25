package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.AiProvider.Usage;
import com.platform.chat.ChatService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The platform admin reads how many AI tokens a shop used on a day, and where they went, down to one conversation. */
class AiUsageIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    UUID idOf(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    @Test
    void everyReplyIsLoggedWithItsRoundsAndTheAdminCanDrillDown() throws Exception {
        String admin = platformToken();
        Tenant shop = onboard("STORE");
        UUID id = idOf(shop);
        String today = LocalDate.now(ZoneId.of("Africa/Cairo")).toString();
        long before = JsonPath.<Number>read(onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage?day=" + today, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.totals.input").longValue();

        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "dress")).withUsage(new Usage(1000, 50, 0)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا فستان حلو").withUsage(new Usage(1200, 80, 900)));
        var reply = chat.reply(id, "WEB", "visitor-usage-1", null, "عايزة فستان");
        chat.reply(id, "WEB", "visitor-usage-2", null, "مرحبا");   // answered without the model

        String day = onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage?day=" + today, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(day, "$.totals.input").longValue()).isEqualTo(before + 2200);
        assertThat(JsonPath.<List<Object>>read(day, "$.byHour")).hasSize(24);
        assertThat(JsonPath.<List<String>>read(day, "$.byStep[*].step")).contains("search_products", "(final answer)");
        assertThat(JsonPath.<List<String>>read(day, "$.shops[*].name")).contains(JsonPath.<String>read(
                onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage/shops/" + id + "?day=" + today, null).andReturn().getResponse().getContentAsString(), "$.shop.name"));

        String s = onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage/shops/" + id + "?day=" + today, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(s, "$.totals.input").intValue()).isEqualTo(2200);
        assertThat(JsonPath.<Number>read(s, "$.totals.cached").intValue()).isEqualTo(900);
        assertThat(JsonPath.<Number>read(s, "$.totals.instant").intValue()).isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(s, "$.conversations")).hasSize(2);
        String conv = JsonPath.read(s, "$.conversations[0].conversationId");   // the biggest one is the model conversation
        String c = onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage/conversations/" + conv, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(c, "$.replies")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(c, "$.replies[0].calls")).hasSize(2);
        assertThat(JsonPath.<String>read(c, "$.replies[0].preview")).isEqualTo("عايزة فستان");
        assertThat(reply.text()).contains("فستان");

        onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage?day=not-a-date", null).andExpect(status().isBadRequest());
        onHost("platform.test", shop.access(), "GET", "/api/v1/platform/ai-usage", null).andExpect(status().isForbidden());
    }
}

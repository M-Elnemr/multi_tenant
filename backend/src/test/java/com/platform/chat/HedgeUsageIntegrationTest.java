package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.IntegrationTestBase;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The tokens of a hedge's losing twin reach the usage log (feature HEDGE) on the shop of the reply, and the admin usage page counts them, so the cost shown is the cost billed. */
class HedgeUsageIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void theLosingTwinIsLoggedOnTheRepliesShopAndShownInTheAdminUsage() throws Exception {
        Tenant shop = onboard("STORE");
        UUID shopId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        String admin = platformToken();
        var n = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            if (n.incrementAndGet() == 1) try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56,\"prompt_cache_hit_tokens\":1000}}".getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* client moved on */ }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        long saved = OpenAiProvider.HEDGE_MS;
        try {
            OpenAiProvider.HEDGE_MS = 300;
            var p = new OpenAiProvider("glm", "GLM", HiveKeyPool.of("a-key-1111", "b-key-2222"), "m", () -> "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, "", 0);
            AiBudget.start(45_000);
            AiBudget.forTenant(shopId);
            try { p.next("s", List.of(Turn.user("hi")), List.of()); } finally { AiBudget.clear(); }
            long deadline = System.currentTimeMillis() + 10_000;
            List<java.util.Map<String, Object>> rows = List.of();
            while (System.currentTimeMillis() < deadline && rows.isEmpty()) {
                rows = jdbc.sql("SELECT model, input_tokens, output_tokens, cached_tokens, conversation_id FROM chat.ai_usage_log WHERE tenant_id = :t AND feature = 'HEDGE'").param("t", shopId).query().listOfRows();
                if (rows.isEmpty()) Thread.sleep(100);
            }
            assertThat(rows).as("the loser's tokens are logged").hasSize(1);
            assertThat(rows.get(0)).containsEntry("model", "glm");
            assertThat(((Number) rows.get(0).get("input_tokens")).intValue()).isEqualTo(1234);
            assertThat(((Number) rows.get(0).get("output_tokens")).intValue()).isEqualTo(56);
            assertThat(((Number) rows.get(0).get("cached_tokens")).intValue()).isEqualTo(1000);
            String today = LocalDate.now(ZoneId.of("Africa/Cairo")).toString();
            String day = onHost("platform.test", admin, "GET", "/api/v1/platform/ai-usage/shops/" + shopId + "?day=" + today, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(day, "$.byFeature[*].feature")).contains("HEDGE");
        } finally {
            OpenAiProvider.HEDGE_MS = saved;
            server.stop(0);
        }
    }
}

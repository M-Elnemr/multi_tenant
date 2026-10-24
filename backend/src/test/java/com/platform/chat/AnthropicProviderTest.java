package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.chat.AiProvider.ToolResult;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Claude provider against a small local server that behaves like the Messages endpoint. */
class AnthropicProviderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    volatile int status = 200;
    volatile String answer;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    AnthropicProvider provider(String key) { return new AnthropicProvider(key, "claude-haiku-5-5", "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "1h", 1500); }

    @Test
    void cachesTheSharedPartForAnHourAndKeepsTheCustomerPartOutOfTheCache() throws Exception {
        answer = "{\"content\":[{\"type\":\"text\",\"text\":\"أهلاً\"}],\"usage\":{\"input_tokens\":20,\"cache_read_input_tokens\":3000,\"cache_creation_input_tokens\":0,\"output_tokens\":7}}";
        var tools = List.of(new ToolDef("search_products", "Search", Map.of("type", "OBJECT", "properties", Map.of("query", Map.of("type", "STRING")), "required", List.of("query"))));
        Turn t = provider("sk-ant-test").next("SHARED RULES" + AiProvider.CACHE_BREAK + "CUSTOMER MEMORY", List.of(Turn.user("hi"), Turn.model("hello"), Turn.user("dress")), tools);
        assertThat(t.text()).isEqualTo("أهلاً");
        assertThat(t.usage().cached()).isEqualTo(3000);
        assertThat(t.usage().input()).isEqualTo(3020);
        assertThat(t.usage().output()).isEqualTo(7);
        assertThat(keys.get(0)).isEqualTo("sk-ant-test");
        JsonNode body = JSON.readTree(bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("SHARED RULES");
        assertThat(body.path("system").get(0).path("cache_control").path("ttl").asText()).isEqualTo("1h");
        assertThat(body.path("system").get(1).path("text").asText()).isEqualTo("CUSTOMER MEMORY");
        assertThat(body.path("system").get(1).has("cache_control")).isFalse();
        assertThat(body.path("tools").get(0).path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(body.path("messages")).hasSize(3);
    }

    @Test
    void aToolExchangeReplaysTheModelsOwnBlocksAndPairsTheResultId() throws Exception {
        answer = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search_products\",\"input\":{\"query\":\"dress\"}}],\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}";
        Turn call = provider("k").next("sys", List.of(Turn.user("dress")), List.of());
        assertThat(call.calls()).hasSize(1);
        assertThat(call.calls().get(0).args()).containsEntry("query", "dress");
        answer = "{\"content\":[{\"type\":\"text\",\"text\":\"found\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
        provider("k").next("sys", List.of(Turn.user("dress"), call, Turn.tool(List.of(new ToolResult("search_products", Map.of("count", 1))))), List.of());
        JsonNode msgs = JSON.readTree(bodies.get(1)).path("messages");
        assertThat(msgs.get(1).path("content").get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(msgs.get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("toolu_1");
    }

    @Test
    void noKeyAndServerErrorsMeanUnavailable() {
        assertThat(provider("").available()).isFalse();
        assertThatThrownBy(() -> provider("").next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        answer = "{\"error\":{}}";
        status = 400;
        assertThatThrownBy(() -> provider("k").next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        assertThat(bodies).hasSize(1);
    }
}

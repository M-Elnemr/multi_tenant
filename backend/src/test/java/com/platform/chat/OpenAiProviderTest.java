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

/** The OpenAI provider against a small local server that behaves like the Chat Completions endpoint. */
class OpenAiProviderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final List<String> auth = new ArrayList<>();
    volatile int status = 200;
    volatile String answer;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    OpenAiProvider provider(String key) { return new OpenAiProvider("openai", "OpenAI", key, "gpt-6-luna", "none", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 1500, false, ""); }

    @Test
    void deepSeekIsTheSameProtocolUnderItsOwnCodeWithMaxTokensExtraSettingsAndItsCacheCounter() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"تمام\"}}],\"usage\":{\"prompt_tokens\":4500,\"completion_tokens\":40,\"prompt_cache_hit_tokens\":3600,\"prompt_cache_miss_tokens\":900}}";
        var ds = new OpenAiProvider("deepseek", "DeepSeek", "sk-ds", "deepseek-v4.1-flash-1017", "", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", 1000, true, "{\"thinking\":{\"type\":\"disabled\"}}");
        assertThat(ds.code()).isEqualTo("deepseek");
        assertThat(ds.label()).isEqualTo("DeepSeek (deepseek-v4.1-flash-1017)");
        Turn t = ds.next("sys", List.of(Turn.user("hi")), List.of());
        assertThat(t.usage().input()).isEqualTo(4500);
        assertThat(t.usage().cached()).isEqualTo(3600);
        JsonNode body = JSON.readTree(bodies.get(0));
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1000);
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThatThrownBy(() -> new OpenAiProvider("deepseek", "DeepSeek", "k", "m", "", "http://x", 10, true, "not json").requestBody("s", List.of(Turn.user("a")), List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void sendsTheConversationWithToolsInJsonSchemaAndReasoningOff() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"أهلاً بيكي\"}}]}";
        var tools = List.of(new ToolDef("search_products", "Search", Map.of("type", "OBJECT", "properties", Map.of("query", Map.of("type", "STRING", "description", "words")), "required", List.of("query"))));
        Turn t = provider("sk-test").next("You are a seller", List.of(Turn.user("hello")), tools);
        assertThat(t.text()).isEqualTo("أهلاً بيكي");
        assertThat(auth.get(0)).isEqualTo("Bearer sk-test");
        JsonNode body = JSON.readTree(bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");   // function calling needs reasoning off on this endpoint
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("hello");
        JsonNode params = body.path("tools").get(0).path("function").path("parameters");
        assertThat(params.path("type").asText()).isEqualTo("object");                                  // OBJECT became object
        assertThat(params.path("properties").path("query").path("type").asText()).isEqualTo("string");  // STRING became string
        assertThat(params.path("properties").path("query").path("description").asText()).isEqualTo("words");   // texts are left alone
    }

    @Test
    void aToolCallComesBackWithItsArgumentsAndTheResultIsPairedWithItsId() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{\\\"query\\\":\\\"قميص\\\"}\"}}]}}]}";
        var p = provider("sk-test");
        Turn first = p.next("sys", List.of(Turn.user("عايزة قميص")), List.of());
        assertThat(first.calls()).hasSize(1);
        assertThat(first.calls().get(0).name()).isEqualTo("search_products");
        assertThat(first.calls().get(0).args()).containsEntry("query", "قميص");
        answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"عندي قميص كتان\"}}]}";
        Turn second = p.next("sys", List.of(Turn.user("عايزة قميص"), first, Turn.tool(List.of(new ToolResult("search_products", Map.of("count", 1))))), List.of());
        assertThat(second.text()).isEqualTo("عندي قميص كتان");
        JsonNode msgs = JSON.readTree(bodies.get(1)).path("messages");
        assertThat(msgs.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(msgs.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_abc");   // the assistant turn is replayed as it came
        assertThat(msgs.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(msgs.get(3).path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(msgs.get(3).path("content").asText()).contains("\"count\":1");
    }

    @Test
    void anUnsetKeyAndAFailingServiceAreReportedAsUnavailableNotAsCrashes() {
        assertThat(provider(null).available()).isFalse();
        assertThatThrownBy(() -> provider("").next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        status = 400;
        answer = "{\"error\":{\"message\":\"bad\"}}";
        assertThatThrownBy(() -> provider("sk-test").next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        assertThat(bodies).hasSize(1);   // a request error is not retried
        bodies.clear();
        status = 503;
        assertThatThrownBy(() -> provider("sk-test").next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        assertThat(bodies).hasSize(3);   // a busy service is tried twice more, with a pause
    }
}

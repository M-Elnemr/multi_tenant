package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.chat.AiProvider.ToolResult;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DeepSeek's thinking mode: the step that calls a tool comes back with {@code reasoning_content}, which must be sent again with the tool results (the service refuses the next
 * request or loses the thread otherwise). It is replayed only with a tool call, never added to a plain answer.
 */
class OpenAiProviderReasoningTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile String reply;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    OpenAiProvider provider() { return new OpenAiProvider("deepseek", "DeepSeek", "k", "m", "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, ""); }

    static final String TOOL_STEP = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"the customer wants a mug, search first\","
            + "\"tool_calls\":[{\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{\\\"query\\\":\\\"mug\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
    static final String ANSWER = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"here is a mug\",\"reasoning_content\":\"done thinking\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}";

    @Test
    void theThinkingOfAToolStepIsSentBackWithTheToolResult() throws Exception {
        List<ToolDef> tools = List.of(new ToolDef("search_products", "search", Map.of("type", "OBJECT", "properties", Map.of())));
        reply = TOOL_STEP;
        OpenAiProvider p = provider();
        List<Turn> turns = new ArrayList<>(List.of(Turn.user("عايز مج")));
        Turn step = p.next("system", turns, tools);
        assertThat(step.calls()).hasSize(1);
        turns.add(step);
        turns.add(Turn.tool(List.of(new ToolResult("search_products", Map.of("products", List.of())))));
        reply = ANSWER;
        Turn answer = p.next("system", turns, tools);
        assertThat(answer.text()).isEqualTo("here is a mug");

        JsonNode second = requests.get(1).path("messages");
        JsonNode assistant = second.get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("reasoning_content").asText()).isEqualTo("the customer wants a mug, search first");
        assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo("call_abc");
        JsonNode tool = second.get(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(requests.get(0).path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void aPlainAnswerDoesNotCarryItsThinkingIntoTheNextRequest() throws Exception {
        reply = ANSWER;
        OpenAiProvider p = provider();
        List<Turn> turns = new ArrayList<>(List.of(Turn.user("hi")));
        Turn a = p.next("system", turns, List.of());
        turns.add(a);
        turns.add(Turn.user("and?"));
        p.next("system", turns, List.of());
        JsonNode assistant = requests.get(1).path("messages").get(2);
        assertThat(assistant.has("reasoning_content")).isFalse();
    }

    @Test
    void aToolCallTheServiceSentWithoutAnIdGetsOneSoItsResultCanBeMatched() throws Exception {
        List<ToolDef> tools = List.of(new ToolDef("search_products", "search", Map.of("type", "OBJECT", "properties", Map.of())));
        reply = TOOL_STEP.replace("\"id\":\"call_abc\",", "");
        OpenAiProvider p = provider();
        List<Turn> turns = new ArrayList<>(List.of(Turn.user("عايز مج")));
        Turn step = p.next("system", turns, tools);
        turns.add(step);
        turns.add(Turn.tool(List.of(new ToolResult("search_products", Map.of()))));
        reply = ANSWER;
        p.next("system", turns, tools);
        JsonNode second = requests.get(1).path("messages");
        assertThat(second.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_0");
        assertThat(second.get(3).path("tool_call_id").asText()).isEqualTo("call_0");
    }
}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What an evaluation sends to a model is checked on the wire: the reasoning setting under test, the output cap and the temperature replace the normal ones only inside the evaluation. */
class OpenAiProviderOverrideIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    private HttpServer server;
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private OpenAiProvider provider(String code, String effort, String extra) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            sent.set(new ObjectMapper().readTree(ex.getRequestBody().readAllBytes()));
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\",\"reasoning_content\":\"hmm\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"completion_tokens_details\":{\"reasoning_tokens\":3}}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return new OpenAiProvider(code, code, "k", code + "-model", effort, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 1000, true, extra);
    }

    @Test
    void theReasoningSettingUnderTestReplacesTheNormalOneOnlyInsideTheEvaluation() throws Exception {
        var qwen = provider("qwen", "", "{\"enable_thinking\":false}");
        qwen.next("system", List.of(AiProvider.Turn.user("hi")), List.of());   // normal use: thinking off, the model's own cap, no temperature
        assertThat(sent.get().path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(sent.get().path("max_tokens").asInt()).isEqualTo(1000);
        assertThat(sent.get().has("temperature")).isFalse();

        var scope = new ChatService.EvalScope(false, 0.3, 2000, Map.of("qwen", Map.of("enable_thinking", true, "thinking_budget", 256)));
        chat.inEvalScope(scope, () -> qwen.next("system", List.of(AiProvider.Turn.user("hi")), List.of()));
        assertThat(sent.get().path("enable_thinking").asBoolean(false)).isTrue();
        assertThat(sent.get().path("thinking_budget").asInt()).isEqualTo(256);
        assertThat(sent.get().path("max_tokens").asInt()).isEqualTo(2000);
        assertThat(sent.get().path("temperature").asDouble()).isEqualTo(0.3);
        // what the provider layer saw and what came back (reasoning tokens and content) is recorded for the report
        assertThat(scope.request).containsEntry("enable_thinking", true).containsEntry("thinking_budget", 256).containsEntry("max_tokens", 2000);
        assertThat(scope.callsWithReasoning).isEqualTo(1);
        assertThat(scope.reasoningTokens).isEqualTo(3);

        var deepseek = provider("deepseek", "none", "");
        var dsScope = new ChatService.EvalScope(false, 0.3, 2000, Map.of("deepseek", Map.of("reasoning_effort", "low")));
        chat.inEvalScope(dsScope, () -> deepseek.next("system", List.of(AiProvider.Turn.user("hi")), List.of()));
        assertThat(sent.get().path("reasoning_effort").asText()).isEqualTo("low");   // not "none"
        // an override for another model does not touch this one
        var other = new ChatService.EvalScope(false, 0.3, 2000, Map.of("qwen", Map.of("enable_thinking", true)));
        chat.inEvalScope(other, () -> deepseek.next("system", List.of(AiProvider.Turn.user("hi")), List.of()));
        assertThat(sent.get().path("reasoning_effort").asText()).isEqualTo("none");
    }
}

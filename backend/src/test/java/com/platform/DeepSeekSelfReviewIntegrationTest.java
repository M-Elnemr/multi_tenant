package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.ChatService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The production setup: DeepSeek answers and DeepSeek itself reviews (AI_REVIEW_MODEL empty), GLM only as the fallback. A valid draft is kept, a hung or failing review costs the customer at most
 * the review timeout, is not retried, and never turns GLM into a reviewer or the answerer of that reply.
 */
class DeepSeekSelfReviewIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Deque<Function<JsonNode, byte[]>> DEEPSEEK = new ArrayDeque<>();
    static final List<JsonNode> DEEPSEEK_SEEN = new CopyOnWriteArrayList<>(), GLM_SEEN = new CopyOnWriteArrayList<>();
    static final HttpServer HIVE;
    static volatile int failFromCall = Integer.MAX_VALUE;   // the Nth DeepSeek request of a test and later answer HTTP 500

    static {
        try {
            HIVE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HIVE.createContext("/v1/chat/completions", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                boolean glm = req.path("model").asText().contains("glm");
                int n;
                Function<JsonNode, byte[]> f = null;
                if (glm) { GLM_SEEN.add(req); n = 0; }
                else { DEEPSEEK_SEEN.add(req); n = DEEPSEEK_SEEN.size(); synchronized (DeepSeekSelfReviewIntegrationTest.class) { f = DEEPSEEK.poll(); } }
                byte[] out = f == null ? text("ok") : f.apply(req);
                int code = !glm && n >= failFromCall ? 500 : 200;
                try { ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* the client moved on */ }
                ex.close();
            });
            HIVE.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            HIVE.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + HIVE.getAddress().getPort() + "/v1";
        r.add("app.ai.glm.base-url", () -> base);
        r.add("app.ai.deepseek.base-url", () -> base);
        r.add("app.ai.glm.api-key", () -> "test-hive-key");
        r.add("app.ai.deepseek.api-key", () -> "test-hive-key");
        r.add("app.ai.review.enabled", () -> "true");
        r.add("app.ai.review.model", () -> "");   // as in production: the answering model reviews
        r.add("app.ai.review.timeout-ms", () -> "1500");
        r.add("app.ai.glm.max-rps", () -> "0");
        r.add("app.ai.deepseek.max-rps", () -> "0");
    }

    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        synchronized (DeepSeekSelfReviewIntegrationTest.class) { DEEPSEEK.clear(); }
        DEEPSEEK_SEEN.clear(); GLM_SEEN.clear(); failFromCall = Integer.MAX_VALUE;
        FakeAiProvider.reset();
    }

    static byte[] json(Map<String, Object> message) {
        try { return JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message)), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] text(String t) { return json(Map.of("role", "assistant", "content", t)); }

    static byte[] toolCall(String name, Map<String, Object> args) {
        try { return json(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_1", "type", "function", "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args)))))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static void deepseek(Function<JsonNode, byte[]> f) { synchronized (DeepSeekSelfReviewIntegrationTest.class) { DEEPSEEK.add(f); } }

    record Shop(UUID id, String name) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "قميص قطن " + uniq();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"name\":\"%s\",\"variants\":[{\"sku\":\"S-%s\",\"priceMinor\":15000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), branch))
                .andExpect(status().isCreated());
        return new Shop(jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), name);
    }

    static final String DRAFT = "القميص القطن بـ 150 جنيه ومتاح، تحب أحجزهولك؟";

    /** DeepSeek searches, then writes the draft; its third call (if any) is the review. */
    void answersWithAPrice(Shop s) {
        deepseek(req -> toolCall("search_products", Map.of("query", s.name())));
        deepseek(req -> text(DRAFT));
    }

    String ask(Shop s, String message) { return chat.reply(s.id(), "WEB", "ds-" + uniq(), null, message).text(); }

    @Test
    void deepSeekReviewsItsOwnDraftAndAValidDraftIsKept() throws Exception {
        Shop s = shop();
        answersWithAPrice(s);
        deepseek(req -> text("{\"ok\":true}"));
        assertThat(ask(s, "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
        assertThat(DEEPSEEK_SEEN).as("search, answer, review: all on DeepSeek").hasSize(3);
        assertThat(DEEPSEEK_SEEN.get(2).path("messages").get(0).path("content").asText()).as("the third call is the reviewer's prompt").contains("senior sales manager");
        assertThat(DEEPSEEK_SEEN.get(2).has("tools")).isFalse();
        assertThat(DEEPSEEK_SEEN.get(0).path("reasoning_effort").asText()).as("the conversation keeps its reasoning level").isEqualTo("low");
        assertThat(DEEPSEEK_SEEN.get(1).path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(DEEPSEEK_SEEN.get(2).path("reasoning_effort").asText()).as("the review is a short check: no reasoning").isEqualTo("none");
        assertThat(GLM_SEEN).as("GLM is not a reviewer").isEmpty();
    }

    @Test
    void aHungReviewCostsAtMostTheReviewTimeoutIsNotRetriedAndGlmIsNeverCalled() throws Exception {
        Shop s = shop();
        answersWithAPrice(s);
        deepseek(req -> { try { Thread.sleep(6_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return text("{\"ok\":true}"); });
        long t = System.nanoTime();
        String reply = ask(s, "بكام " + s.name() + "؟");
        double took = (System.nanoTime() - t) / 1e9;
        assertThat(reply).isEqualTo(DRAFT);
        assertThat(took).as("sent after the 1.5 s review cap, not after the hung review").isLessThan(5.0);
        assertThat(DEEPSEEK_SEEN).as("search, answer, one review try: no retry, no twin").hasSize(3);
        assertThat(GLM_SEEN).as("a slow review does not move the reply to the fallback model").isEmpty();
    }

    @Test
    void aFailingReviewSendsTheDraftAndNeverTurnsGlmIntoTheReviewerOrAnswerer() throws Exception {
        Shop s = shop();
        answersWithAPrice(s);
        deepseek(req -> text("server error"));
        failFromCall = 3;   // the review call answers HTTP 500
        assertThat(ask(s, "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
        assertThat(DEEPSEEK_SEEN).as("no retry of the review").hasSize(3);
        assertThat(GLM_SEEN).as("the failed review is not handed to GLM, and the reply is not restarted on it").isEmpty();
    }
}

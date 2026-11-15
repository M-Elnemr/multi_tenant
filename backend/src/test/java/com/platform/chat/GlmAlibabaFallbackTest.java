package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GLM (through a Hive key pool) with Alibaba Qwen as the per-request fallback, both as real OpenAI-protocol providers talking to local servers that play the two services.
 * Mocked services: this proves OUR behaviour against specified failures, not that real GLM/Hive/Alibaba credentials work.
 */
class GlmAlibabaFallbackTest {
    record Reply(int status, String body, long delayMs) {}

    static String ok(String text) { return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + q(text) + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}"; }
    static String q(String s) { try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s); } catch (Exception e) { throw new RuntimeException(e); } }
    static final String TOOL_CALL = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";

    HttpServer glmServer, qwenServer;
    final List<String> glmBodies = new CopyOnWriteArrayList<>(), qwenBodies = new CopyOnWriteArrayList<>(), glmAuth = new CopyOnWriteArrayList<>();
    volatile Supplier<Reply> glmBehaviour = () -> new Reply(200, ok("from glm"), 0), qwenBehaviour = () -> new Reply(200, ok("from qwen"), 0);

    HttpServer serve(List<String> bodies, List<String> auth, java.util.function.Supplier<Supplier<Reply>> behaviour) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (auth != null) auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            Reply r = behaviour.get().get();
            try { if (r.delayMs() > 0) Thread.sleep(r.delayMs()); } catch (InterruptedException ignored) { }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(r.status(), out.length); ex.getResponseBody().write(out); } catch (java.io.IOException gone) { /* the caller timed out */ }
            ex.close();
        });
        s.start();
        return s;
    }

    @BeforeEach
    void start() throws Exception {
        glmServer = serve(glmBodies, glmAuth, () -> glmBehaviour);
        qwenServer = serve(qwenBodies, null, () -> qwenBehaviour);
    }

    @AfterEach
    void stop() { glmServer.stop(0); qwenServer.stop(0); AiBudget.clear(); }

    String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort() + "/v1"; }

    ChannelAi.Routed route(String... glmKeys) {
        AiProvider glm = new OpenAiProvider("glm", "GLM", HiveKeyPool.of(glmKeys), "zai-org/glm-5.3-flash", () -> "low", url(glmServer), 1000, true, "", 0);
        AiProvider qwen = new OpenAiProvider("qwen", "Alibaba Qwen", "qwen-key", "qwen3.7-flash", "", url(qwenServer), 1000, true, "", 0);
        AiProvider claude = new ChannelAiTest.Scripted("anthropic", false);
        return new ChannelAi(glm, claude, glm, qwen, "default", "default").forChannel("WEB");
    }

    static final List<Turn> ASK = List.of(Turn.user("عايز تيشيرت"));
    static final List<ToolDef> TOOLS = List.of(new ToolDef("search_products", "search", Map.of("type", "OBJECT", "properties", Map.of())));

    Turn ask(ChannelAi.Routed r) { return r.next("SYSTEM-SHOP-A rules", ASK, TOOLS); }

    @Test
    void aNormalGlmAnswerNeverCallsAlibaba() {
        var r = route("k1");
        assertThat(ask(r).text()).isEqualTo("from glm");
        assertThat(r.fellBack()).isFalse();
        assertThat(glmBodies).hasSize(1);
        assertThat(qwenBodies).isEmpty();
    }

    @Test
    void http429On_oneKey_goesStraightToAlibabaWithoutRetryingGlm() {
        glmBehaviour = () -> new Reply(429, "{\"error\":\"rate\"}", 0);
        var r = route("k1");
        long t0 = System.nanoTime();
        assertThat(ask(r).text()).isEqualTo("from qwen");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("no sleeping between GLM and the fallback").isLessThan(900);
        assertThat(glmBodies).hasSize(1);
        assertThat(qwenBodies).hasSize(1);
        assertThat(r.fellBack()).isTrue();
    }

    @Test
    void http429WithTwoKeysTriesTheOtherKeyOnceThenAlibaba() {
        glmBehaviour = () -> new Reply(429, "{}", 0);
        var r = route("k1", "k2");
        assertThat(ask(r).text()).isEqualTo("from qwen");
        assertThat(glmBodies).hasSize(2);
        assertThat(glmAuth.stream().distinct()).as("the retry used the other key").hasSize(2);
        assertThat(qwenBodies).hasSize(1);
    }

    @Test
    void serverErrorsGoToAlibabaAtOnceAndGlmIsCalledOnce() {
        for (int status : new int[] {500, 502, 503, 504}) {
            glmBodies.clear(); qwenBodies.clear();
            glmBehaviour = () -> new Reply(status, "{\"error\":\"down\"}", 0);
            var r = route("k1", "k2");
            assertThat(ask(r).text()).as("status " + status).isEqualTo("from qwen");
            assertThat(glmBodies).as("status " + status).hasSize(1);
            assertThat(qwenBodies).hasSize(1);
        }
    }

    @Test
    void aConnectionFailureGoesToAlibaba() {
        var r = route("k1");
        glmServer.stop(0);
        assertThat(ask(r).text()).isEqualTo("from qwen");
        assertThat(r.fellBack()).isTrue();
    }

    @Test
    void aTimeoutGoesToAlibaba() {
        glmBehaviour = () -> new Reply(200, ok("too late"), 7_000);
        AiBudget.start(5_000);   // a reply with 5 s left: the call may wait about 4 s
        var r = route("k1");
        long t0 = System.nanoTime();
        Turn t = r.next("s", ASK, TOOLS);
        assertThat(t.text()).isEqualTo("from qwen");
        assertThat(glmBodies).hasSize(1);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(6_500);
    }

    @Test
    void emptyAndMalformedAndErrorBodiesGoToAlibaba() {
        List<String> badBodies = List.of(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}",      // empty
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":\"stop\"}]}",    // null
                "{\"choices\":[]}",                                                                                       // no choice
                "not json at all <html>502</html>",                                                                       // malformed
                "{\"choices\":[{\"message\":{\"con",                                                                       // truncated
                "{\"error\":{\"message\":\"The model is overloaded\",\"code\":\"1305\"}}",                                // provider error object, HTTP 200
                "{\"code\":1305,\"msg\":\"busy\",\"success\":false}",                                                       // provider error envelope
                "{\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"error\"}]}");                    // failing finish reason
        for (String body : badBodies) {
            glmBodies.clear(); qwenBodies.clear();
            glmBehaviour = () -> new Reply(200, body, 0);
            var r = route("k1");
            assertThat(ask(r).text()).as(body).isEqualTo("from qwen");
            assertThat(glmBodies).as("no retry loop on GLM for " + body).hasSize(1);
            assertThat(qwenBodies).hasSize(1);
        }
    }

    @Test
    void aCapacityMessageInsideAnHttp200AnswerIsNeverDeliveredAndAlibabaAnswers() {
        for (String msg : ProviderFailureTest.OUTAGE) {
            glmBodies.clear(); qwenBodies.clear();
            glmBehaviour = () -> new Reply(200, ok(msg), 0);
            var r = route("k1");
            Turn t = ask(r);
            assertThat(t.text()).as(msg).isEqualTo("from qwen");
            assertThat(t.text()).doesNotContain(msg);
            assertThat(qwenBodies).hasSize(1);
        }
    }

    @Test
    void aGenuineRefusalOrOrdinaryAnswerIsDeliveredAsGlmWrittenIt() {
        for (String msg : ProviderFailureTest.NORMAL) {
            glmBodies.clear(); qwenBodies.clear();
            glmBehaviour = () -> new Reply(200, ok(msg), 0);
            var r = route("k1");
            assertThat(ask(r).text()).as(msg).isEqualTo(msg);
            assertThat(qwenBodies).as("no fallback for: " + msg).isEmpty();
        }
    }

    @Test
    void whenAlibabaFailsTooTheCustomerGetsNoRawProviderText() {
        glmBehaviour = () -> new Reply(200, ok("We are currently experiencing high traffic, try again in a minute."), 0);
        qwenBehaviour = () -> new Reply(503, "{\"error\":\"Alibaba internal secret detail\"}", 0);
        var r = route("k1");
        assertThatThrownBy(() -> ask(r)).isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("high traffic").doesNotContain("secret detail");
                    assertThat(((AiUnavailableException) e).category()).isEqualTo(ProviderFailure.SERVER_ERROR);
                });
        // and when Alibaba also answers with a capacity notice it is discarded as well
        qwenBehaviour = () -> new Reply(200, ok("The server is overloaded. Try again later."), 0);
        var r2 = route("k1");
        assertThatThrownBy(() -> ask(r2)).isInstanceOf(AiUnavailableException.class).satisfies(e -> assertThat(((AiUnavailableException) e).category()).isEqualTo(ProviderFailure.OVERLOAD_MESSAGE));
    }

    @Test
    void aSuccessfulFallbackReceivesTheSameSystemHistoryAndTools() {
        glmBehaviour = () -> new Reply(503, "{}", 0);
        var r = route("k1");
        List<Turn> history = List.of(Turn.user("عايز تيشيرت"), Turn.model("مقاسك كام؟"), Turn.user("L"));
        r.next("SYSTEM-SHOP-A rules", history, TOOLS);
        String qb = qwenBodies.get(0), gb = glmBodies.get(0);
        for (String must : new String[] {"SYSTEM-SHOP-A rules", "عايز تيشيرت", "مقاسك كام؟", "search_products"}) {
            assertThat(gb).contains(must);
            assertThat(qb).as("fallback request lacks: " + must).contains(must);
        }
        assertThat(qb).contains("qwen3.7-flash").doesNotContain("glm-5.3");
    }

    @Test
    void everyNewRequestTriesGlmFirstThereIsNoMemoryOfEarlierFailures() {
        var failing = new java.util.concurrent.atomic.AtomicInteger(5);
        glmBehaviour = () -> failing.getAndDecrement() > 0 ? new Reply(503, "{}", 0) : new Reply(200, ok("glm is back"), 0);
        for (int i = 0; i < 5; i++) assertThat(ask(route("k1")).text()).isEqualTo("from qwen");   // five failures in a row ...
        int glmCallsSoFar = glmBodies.size();
        var next = route("k1");
        assertThat(ask(next).text()).as("... and the sixth request still goes to GLM first").isEqualTo("glm is back");
        assertThat(glmBodies.size()).isEqualTo(glmCallsSoFar + 1);
        assertThat(next.fellBack()).isFalse();
    }

    @Test
    void aFailureAfterATool_roundIsNotSwitchedMidwayButTheWholeReplyRestartsOnAlibaba() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        glmBehaviour = () -> calls.getAndIncrement() == 0 ? new Reply(200, TOOL_CALL, 0) : new Reply(503, "{}", 0);
        var r = route("k1");
        Turn first = ask(r);
        assertThat(first.calls()).hasSize(1);
        List<Turn> next = new ArrayList<>(ASK); next.add(first); next.add(Turn.tool(List.of()));
        assertThatThrownBy(() -> r.next("s", next, TOOLS)).isInstanceOf(AiUnavailableException.class);
        assertThat(qwenBodies).as("no model switch in the middle of a tool exchange").isEmpty();
        var restarted = r.restartOnFallback();
        assertThat(restarted.code()).isEqualTo("qwen");
        assertThat(restarted.next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
    }

    @Test
    void twoShopsRepliesNeverShareContentThroughTheFallback() {
        glmBehaviour = () -> new Reply(503, "{}", 0);
        route("k1").next("SYSTEM tenant A catalog: SECRET-A-ITEM", List.of(Turn.user("A asks")), TOOLS);
        route("k1").next("SYSTEM tenant B catalog: B-ITEM", List.of(Turn.user("B asks")), TOOLS);
        assertThat(qwenBodies).hasSize(2);
        assertThat(qwenBodies.get(1)).contains("B-ITEM").doesNotContain("SECRET-A-ITEM").doesNotContain("A asks");
    }
}

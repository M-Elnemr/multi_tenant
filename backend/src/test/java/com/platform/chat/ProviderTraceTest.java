package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every model request attempt reaches the reply's timeline, the failed ones with their full duration, a hedge's two requests separately, the fallback with what the failed model cost. Against
 * two local stub servers standing in for GLM and Qwen: no real provider is called (synthetic timings, not production data).
 */
class ProviderTraceTest {
    static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":9}}";
    final List<ReplyTrace> done = new CopyOnWriteArrayList<>();
    Consumer<ReplyTrace> savedSink;
    long savedHedge;
    HttpServer glm, qwen;
    final AtomicInteger glmCalls = new AtomicInteger(), qwenCalls = new AtomicInteger();
    volatile int glmStatus = 200;
    volatile long glmDelayMs = 0, glmFirstDelayMs = 0;
    volatile String lastBody = "";

    HttpServer server(AtomicInteger calls, java.util.function.Supplier<Integer> status, java.util.function.LongSupplier delay, java.util.function.BooleanSupplier firstOnly) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            long d = delay.getAsLong();
            if (d > 0 && (!firstOnly.getAsBoolean() || n == 1)) try { Thread.sleep(d); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int st = status.get();
            byte[] out = (st == 200 ? OK : "{\"error\":\"busy\"}").getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(st, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* the client gave up */ }
            ex.close();
        });
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.start();
        return s;
    }

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        savedSink = ReplyTrace.sink;
        ReplyTrace.sink = done::add;
        ReplyTrace.enabled = true;
        glm = server(glmCalls, () -> glmStatus, () -> glmFirstDelayMs > 0 ? glmFirstDelayMs : glmDelayMs, () -> glmFirstDelayMs > 0);
        qwen = server(qwenCalls, () -> 200, () -> 0, () -> false);
    }

    @AfterEach
    void stop() { glm.stop(0); qwen.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; OpenAiProvider.LOST_TWIN_SINK = null; ReplyTrace.sink = savedSink; ReplyTrace.role(null); ReplyTrace.purpose(null); }

    OpenAiProvider glm() { return new OpenAiProvider("glm", "GLM", "glm-secret-key", "glm-5", "low", "http://127.0.0.1:" + glm.getAddress().getPort() + "/v1", 500, true, ""); }

    OpenAiProvider qwen() { return new OpenAiProvider("qwen", "Qwen", "qwen-secret-key", "qwen-plus", "", "http://127.0.0.1:" + qwen.getAddress().getPort() + "/v1", 500, true, ""); }

    ReplyTrace run(Runnable body) {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        try { body.run(); } finally { ReplyTrace.finish(false, null); }
        return t;
    }

    static List<ReplyTrace.Event> all(ReplyTrace t, String n) { return ReplyTraceTest.all(t, n); }

    // ---- success --------------------------------------------------------------------------------------------------------------

    @Test
    void aSuccessfulCallIsRecordedWithItsAttemptTokensAndRole() {
        ReplyTrace t = run(() -> glm().next("system", List.of(Turn.user("customer-secret-text")), List.of()));
        var call = all(t, "provider_call");
        assertThat(call).hasSize(1);
        assertThat(call.get(0).a()).containsEntry("provider", "glm").containsEntry("model", "glm-5").containsEntry("role", "primary").containsEntry("purpose", "answer")
                .containsEntry("usable", true).containsEntry("outcome", "ok").containsEntry("attempts", 1).containsEntry("in", 120).containsEntry("out", 9).containsEntry("hedged", false);
        assertThat(all(t, "provider_request_started")).hasSize(1);
        var attempt = all(t, "provider_response_completed");
        assertThat(attempt).hasSize(1);
        assertThat(attempt.get(0).a()).containsEntry("status", "2xx").containsEntry("retry", false).containsEntry("usable", true);
        assertThat(t.summary()).containsEntry("model_calls", 1L).containsEntry("tokens_in", 120L).containsEntry("tokens_out", 9L).containsEntry("failed_model_ms", 0L);
    }

    @Test
    void theTraceNeverContainsTheKeyTheHeaderThePromptOrTheAnswer() {
        ReplyTrace t = run(() -> glm().next("system prompt with secrets", List.of(Turn.user("customer-secret-text 01012345678")), List.of()));
        String everything = t.snapshot() + String.valueOf(t.summary());
        assertThat(everything).doesNotContain("glm-secret-key").doesNotContain("Bearer").doesNotContain("customer-secret-text").doesNotContain("01012345678").doesNotContain("system prompt").doesNotContain("answer\"");
    }

    // ---- failures --------------------------------------------------------------------------------------------------------------

    @Test
    void aFailedCallStaysInTheTimelineWithItsFullDuration() {
        glmStatus = 500;
        glmDelayMs = 350;
        ReplyTrace t = run(() -> {
            AiBudget.capCalls(5_000);
            assertThatThrownBy(() -> glm().next("system", List.of(Turn.user("hi")), List.of())).isInstanceOf(AiProvider.AiUnavailableException.class);
        });
        var failed = all(t, "provider_request_failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).d()).isGreaterThanOrEqualTo(330L);
        assertThat(failed.get(0).a()).containsEntry("status", "5xx").containsEntry("usable", false);
        var call = all(t, "provider_call").get(0);
        assertThat(call.a()).containsEntry("usable", false).containsEntry("outcome", "failed");
        assertThat(call.d()).isGreaterThanOrEqualTo(330L);
        assertThat(((Number) t.summary().get("failed_model_ms")).longValue()).isGreaterThanOrEqualTo(330L);
    }

    @Test
    void aTimeoutIsRecordedAsATimeoutWithTheTimeSpentWaiting() {
        glmDelayMs = 2_000;
        ReplyTrace t = run(() -> {
            AiBudget.capCalls(400);   // the review's cap: the same code path as a real timeout, in 0.4 s
            assertThatThrownBy(() -> glm().next("system", List.of(Turn.user("hi")), List.of())).isInstanceOf(AiProvider.AiUnavailableException.class);
        });
        var timedOut = all(t, "provider_request_timed_out");
        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.get(0).d()).isBetween(350L, 1_500L);
        assertThat(timedOut.get(0).a()).containsEntry("status", "none").containsEntry("failure", ProviderFailure.TIMEOUT);
        assertThat(all(t, "provider_call").get(0).a()).containsEntry("outcome", "timeout");
    }

    // ---- fallback -------------------------------------------------------------------------------------------------------------

    @Test
    void aFailedPrimaryThenTheFallbackShowsWhatTheFailureCostAndWhichModelAnswered() {
        glmStatus = 500;
        glmDelayMs = 300;   // the failed GLM call costs 0.3 s before Qwen starts
        ReplyTrace t = run(() -> {
            ChannelAi.Routed routed = new ChannelAi.Routed(glm(), qwen(), false);
            AiBudget.start(45_000);
            AiBudget.capCalls(5_000);   // no retry of the failing request (the existing behaviour without a cap is 3 attempts), so the fallback starts after one failure
            Turn turn = routed.next("system", List.of(Turn.user("hi")), List.of());
            assertThat(turn.text()).isEqualTo("answer");
            assertThat(routed.fellBack()).isTrue();
        });
        var calls = all(t, "provider_call");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).a()).containsEntry("provider", "glm").containsEntry("role", "primary").containsEntry("usable", false);
        assertThat(calls.get(1).a()).containsEntry("provider", "qwen").containsEntry("role", "fallback").containsEntry("usable", true);
        var started = all(t, "fallback_started").get(0);
        assertThat(started.a()).containsEntry("from", "glm").containsEntry("to", "qwen");
        assertThat(((Number) started.a().get("waited_ms")).longValue()).isGreaterThanOrEqualTo(280L);   // the stall before the fallback is visible
        assertThat(all(t, "fallback_completed").get(0).a()).containsEntry("ok", true);
        assertThat(t.summary()).containsEntry("fallbacks", 1L).containsEntry("model_calls", 2L);
        assertThat(t.lastUsableProvider()).isEqualTo("qwen");
        // not double counted: the model time is the two calls, one after the other
        long sum = calls.stream().mapToLong(ReplyTrace.Event::d).sum();
        assertThat(t.summary().get("model_ms")).isEqualTo(sum);
        assertThat(glmCalls.get()).isEqualTo(1);   // and instrumenting changed nothing: one failing request, no retry
        assertThat(qwenCalls.get()).isEqualTo(1);
    }

    // ---- hedge ------------------------------------------------------------------------------------------------------------------

    @Test
    void aHedgeShowsBothRequestsOnTheirOwnTheWinnerAndNoDoubleCountedTime() throws Exception {
        OpenAiProvider.HEDGE_MS = 200;
        glmFirstDelayMs = 1_800;   // the first request hangs; its twin, sent at 200 ms, answers at once
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        AiBudget.start(45_000);
        Turn turn = glm().next("system", List.of(Turn.user("hi")), List.of());
        assertThat(turn.text()).isEqualTo("answer");
        long until = System.currentTimeMillis() + 6_000;   // the loser is not cancelled: it ends later, while the reply may already be over, so wait for it before closing
        while (ReplyTraceTest.all(t, "provider_hedge_request").size() < 2 && System.currentTimeMillis() < until) Thread.sleep(25);
        ReplyTrace.finish(false, null);

        var hedged = all(t, "provider_attempt_hedged");
        assertThat(hedged).hasSize(1);
        assertThat(hedged.get(0).a()).containsEntry("allowed", true).containsEntry("after_ms", 200L);
        var reqs = all(t, "provider_hedge_request");
        assertThat(reqs).hasSize(2);
        var twin = reqs.stream().filter(e -> "twin".equals(e.a().get("which"))).findFirst().orElseThrow();
        var first = reqs.stream().filter(e -> "first".equals(e.a().get("which"))).findFirst().orElseThrow();
        assertThat(twin.a()).containsEntry("winner", true).containsEntry("parallel", true).containsEntry("usable", true);
        assertThat(first.a()).containsEntry("winner", false).containsEntry("parallel", true).containsEntry("billed", true);   // the loser answered too: it consumed provider capacity
        assertThat(first.d()).isGreaterThanOrEqualTo(1_700L);
        assertThat(twin.d()).isLessThan(first.d());
        var call = all(t, "provider_call").get(0);
        assertThat(call.a()).containsEntry("hedged", true).containsEntry("usable", true);
        assertThat(call.d()).isLessThan(1_500L);                    // the reply waited for the twin, not for the 1.8 s request
        assertThat(t.summary().get("model_ms")).isEqualTo(call.d());   // the parallel requests are not added to the model time
        assertThat(t.summary()).containsEntry("hedges", 1L);
        assertThat(glmCalls.get()).isEqualTo(2);   // behaviour unchanged: exactly the existing hedge, one twin
    }

    @Test
    void aRetryOnAnotherAttemptIsFlaggedAndCounted() {
        glmStatus = 429;
        ReplyTrace t = run(() -> {
            AiBudget.start(45_000);
            // no key pool: a 429 waits 1 s and retries (existing behaviour); the second attempt is a retry in the timeline
            glmStatus = 429;
            new Thread(() -> { try { Thread.sleep(700); } catch (InterruptedException ignored) { /* test */ } glmStatus = 200; }).start();
            Turn turn = glm().next("system", List.of(Turn.user("hi")), List.of());
            assertThat(turn.text()).isEqualTo("answer");
        });
        var started = all(t, "provider_request_started");
        assertThat(started).hasSize(2);
        assertThat(started.get(0).a()).containsEntry("retry", false);
        assertThat(started.get(1).a()).containsEntry("retry", true);
        assertThat(all(t, "provider_request_failed").get(0).a()).containsEntry("status", "4xx");
        assertThat(all(t, "provider_call").get(0).a()).containsEntry("attempts", 2).containsEntry("usable", true);
    }
}

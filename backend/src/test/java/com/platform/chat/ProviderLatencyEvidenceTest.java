package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The measurements that identify a provider request after the fact (the provider's own request id, the protocol, our id of the key, the load when it was sent), the classification of a hedge twin that
 * WE cancelled, and the per-check timing of the rule checks. Against a local stub standing in for the provider (synthetic data only). None of it changes what a reply does.
 */
class ProviderLatencyEvidenceTest {
    static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":9}}";
    final List<ReplyTrace> done = new CopyOnWriteArrayList<>();
    Consumer<ReplyTrace> savedSink;
    long savedHedge;
    HttpServer stub;
    volatile long delayMs = 0;
    volatile String requestId = UUID.randomUUID().toString();

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        savedSink = ReplyTrace.sink;
        ReplyTrace.sink = done::add;
        ReplyTrace.enabled = true;
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] out = OK.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("x-request-id", requestId);
            try { ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* the client gave up */ }
            ex.close();
        });
        stub.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        stub.start();
    }

    @AfterEach
    void stop() { stub.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; ReplyTrace.sink = savedSink; ReplyTrace.role(null); ReplyTrace.purpose(null); }

    OpenAiProvider glm() { return new OpenAiProvider("glm", "GLM", "glm-secret-key-0123456789", "glm-5", "low", "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1", 500, true, ""); }

    static List<ReplyTrace.Event> all(ReplyTrace t, String n) { return ReplyTraceTest.all(t, n); }

    // ---- request identity ------------------------------------------------------------------------------------------------------

    @Test
    void aCallCarriesTheProvidersRequestIdTheProtocolOurKeyIdAndTheLoad() {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        glm().next("system", List.of(Turn.user("hi")), List.of());
        ReplyTrace.finish(false, null);
        var a = all(t, "provider_call").get(0).a();
        assertThat(a).containsEntry("request_id", requestId).containsEntry("http", "1.1").containsEntry("key", "fixed").containsEntry("inflight", 1);
        var attempt = all(t, "provider_response_completed").get(0).a();
        assertThat(attempt).containsEntry("request_id", requestId).containsEntry("key", "fixed");
        assertThat(t.snapshot().toString()).doesNotContain("glm-secret-key");   // the key itself is nowhere in the trace
    }

    @Test
    void aRequestIdThatIsNotAUuidIsNeverStored() {
        requestId = "+201001234567-call-me";   // whatever a hostile or broken response says: only the strict shape passes
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        glm().next("system", List.of(Turn.user("hi")), List.of());
        ReplyTrace.finish(false, null);
        assertThat(all(t, "provider_call").get(0).a()).containsEntry("request_id", "?");
        assertThat(t.snapshot().toString()).doesNotContain("201001234567");
    }

    @Test
    void aFailedCallStillNamesTheKeyItWasSentWith() {
        delayMs = 6_000;
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        AiBudget.start(5_000);
        AiBudget.capCalls(600);   // the review's kind of cap: the same code path as a real timeout, quickly
        try { glm().next("system", List.of(Turn.user("hi")), List.of()); } catch (AiProvider.AiUnavailableException expected) { /* the timeout */ }
        ReplyTrace.finish(false, null);
        var a = all(t, "provider_call").get(0).a();
        assertThat(a).containsEntry("outcome", "timeout").containsEntry("key", "fixed").containsEntry("http", "none").doesNotContainKey("request_id");
    }

    // ---- twin cancellation ------------------------------------------------------------------------------------------------------

    @Test
    void aTwinThatWeCancelIsCancelledNotAConnectionFailure() throws Exception {
        OpenAiProvider.HEDGE_MS = 1_500;   // as in production the twin is cancelled (hedge + the wait left to it) BEFORE its own timeout could end it: that needs a threshold above 1 s
        delayMs = 12_000;   // the first request and its twin both hang past the call's time
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        AiBudget.start(6_000);
        try { glm().next("system", List.of(Turn.user("hi")), List.of()); } catch (AiProvider.AiUnavailableException expected) { /* nothing answered in time */ }
        long until = System.currentTimeMillis() + 5_000;
        while (all(t, "provider_hedge_request").size() < 2 && System.currentTimeMillis() < until) Thread.sleep(25);
        ReplyTrace.finish(false, null);
        var reqs = all(t, "provider_hedge_request");
        var twin = reqs.stream().filter(e -> "twin".equals(e.a().get("which"))).findFirst().orElseThrow();
        assertThat(twin.a().get("failure")).isEqualTo("CANCELLED");
        var first = reqs.stream().filter(e -> "first".equals(e.a().get("which"))).findFirst().orElseThrow();
        assertThat(first.a().get("failure")).isEqualTo("TIMEOUT");   // its own timeout stays a timeout
        assertThat(twin.a()).containsKey("key");
    }

    @Test
    void theClassificationOfAHedgeRequestThatEndedWithoutAnAnswer() {
        assertThat(OpenAiProvider.hedgeFailure(new CancellationException(), false)).isEqualTo("CANCELLED");
        assertThat(OpenAiProvider.hedgeFailure(new CompletionException(new CancellationException()), false)).isEqualTo("CANCELLED");
        assertThat(OpenAiProvider.hedgeFailure(new java.io.IOException("cancelled"), true)).isEqualTo("CANCELLED");   // whatever the client reports after WE cancelled
        assertThat(OpenAiProvider.hedgeFailure(new java.io.IOException("reset"), false)).isEqualTo(ProviderFailure.CONNECTION);   // a real failure keeps its name
        assertThat(OpenAiProvider.hedgeFailure(new java.net.ConnectException("refused"), false)).isEqualTo(ProviderFailure.CONNECTION);
        assertThat(OpenAiProvider.hedgeFailure(new HttpTimeoutException("request timed out"), true)).isEqualTo(ProviderFailure.TIMEOUT);   // its own timeout is not our cancel
        assertThat(OpenAiProvider.hedgeFailure(new CompletionException(new HttpTimeoutException("t")), false)).isEqualTo(ProviderFailure.TIMEOUT);
    }

    // ---- rule-check laps --------------------------------------------------------------------------------------------------------

    @Test
    void aSlowCheckIsRecordedByNameAndAFastOneIsNot() throws Exception {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.Laps lap = ReplyTrace.laps();
        lap.mark("FAST_ONE");
        Thread.sleep(40);
        lap.mark("SLOW_ONE");
        lap.mark("bad name with +201001234567");   // not a valid check name: recorded as «other», never as text
        ReplyTrace.finish(false, null);
        var steps = all(t, "rule_check_step");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).a()).containsEntry("check", "SLOW_ONE");
        assertThat(steps.get(0).d()).isGreaterThanOrEqualTo(30L);
        assertThat(t.snapshot().toString()).doesNotContain("201001234567");
    }

    @Test
    void aReplyNeverGetsMoreThanTheCapOfCheckSteps() throws Exception {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.Laps lap = ReplyTrace.laps();
        for (int i = 0; i < ReplyTrace.Laps.LAP_MAX_EVENTS + 5; i++) { Thread.sleep(ReplyTrace.Laps.LAP_MIN_MS + 2); lap.mark("STEP"); }
        ReplyTrace.finish(false, null);
        assertThat(all(t, "rule_check_step")).hasSize(ReplyTrace.Laps.LAP_MAX_EVENTS);
    }
}

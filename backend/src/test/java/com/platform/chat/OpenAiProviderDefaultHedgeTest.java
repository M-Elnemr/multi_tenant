package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * The default hedge threshold is 25 seconds (AI_HEDGE_MS) everywhere, and it works in real time: the twin is sent only when the first call is still pending after 25 s, which needs the
 * call itself to be allowed to last longer than that. Two of these tests wait in real time (about 28 s and 5 s).
 */
class OpenAiProviderDefaultHedgeTest {
    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final List<Long> arrivals = new CopyOnWriteArrayList<>();   // when each request reached the server, in nanoseconds
    volatile long firstDelayMs = 0;
    long savedHedge;

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        OpenAiProvider.HEDGE_MS = OpenAiProvider.DEFAULT_HEDGE_MS;   // what production runs with unless AI_HEDGE_MS says otherwise
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            arrivals.add(System.nanoTime());
            int n = calls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            if (n == 1 && firstDelayMs > 0) try { Thread.sleep(firstDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer " + n + "\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* the client moved on */ }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; OpenAiProvider.LOST_TWIN_SINK = null; }

    OpenAiProvider provider() { return new OpenAiProvider("glm", "GLM", HiveKeyPool.of("key-AAAA", "key-BBBB"), "m", () -> "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, "", 0); }

    @Test
    void theDefaultIs25SecondsInTheCodeThePropertiesTheComposeFileAndTheSpringDefault() throws Exception {
        assertThat(OpenAiProvider.DEFAULT_HEDGE_MS).isEqualTo(25_000);
        assertThat(ChannelAi.class.getDeclaredMethod("hedgeMs", long.class).getAnnotation(Value.class).value()).isEqualTo("${app.ai.hedge-ms:25000}");
        Path props = Path.of("src/main/resources/application.properties"), compose = Path.of("..", "docker-compose.test-slim.yml");
        assertThat(Files.readString(props)).contains("app.ai.hedge-ms=${AI_HEDGE_MS:25000}");
        assertThat(Files.readString(compose)).contains("AI_HEDGE_MS: ${AI_HEDGE_MS:-25000}");
    }

    @Test
    void aCallIsAllowedToLastLongerThanTheHedgeThresholdWhateverItIsSetTo() {
        for (long hedge : new long[] {600, 8_000, 17_000, 25_000, 40_000}) {
            OpenAiProvider.HEDGE_MS = hedge;
            assertThat(OpenAiProvider.callTimeoutMs()).as("hedge " + hedge).isGreaterThanOrEqualTo(hedge + 3_000).isGreaterThanOrEqualTo(OpenAiProvider.CALL_TIMEOUT_MS);
        }
        OpenAiProvider.HEDGE_MS = 600;
        assertThat(OpenAiProvider.callTimeoutMs()).as("a short threshold leaves the 20 s call timeout as it was").isEqualTo(OpenAiProvider.CALL_TIMEOUT_MS);
    }

    @Test
    void aCallThatAnswersBeforeTheThresholdIsNeverHedged() {
        firstDelayMs = 4_000;
        AiBudget.start(45_000);
        long t = System.nanoTime();
        assertThat(provider().next("s", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("answer 1");
        assertThat((System.nanoTime() - t) / 1_000_000).isBetween(3_900L, 8_000L);
        assertThat(calls.get()).as("answered after 4 s of 25: sent once").isEqualTo(1);
    }

    @Test
    void theTwinIsSentOnlyAfter25SecondsAndTheStillPendingFirstCallIsKeptAndRecorded() throws Exception {
        var lost = new LinkedBlockingQueue<AiProvider.Usage>();
        OpenAiProvider.LOST_TWIN_SINK = (tenant, model, usage) -> lost.add(usage);
        firstDelayMs = 27_000;   // still pending at 25 s: longer than the old 20 s call timeout
        UUID shop = UUID.randomUUID();
        AiBudget.start(45_000);
        AiBudget.forTenant(shop);
        long t = System.nanoTime();
        Turn turn = provider().next("s", List.of(Turn.user("hi")), List.of());
        double took = (System.nanoTime() - t) / 1e9;
        assertThat(turn.text()).as("the twin answered first").isEqualTo("answer 2");
        assertThat(calls.get()).isEqualTo(2);
        double gap = (arrivals.get(1) - arrivals.get(0)) / 1e9;
        assertThat(gap).as("the twin reached the server no earlier than 25 s after the first call").isBetween(24.8, 27.0);
        assertThat(took).as("the reply took about 25 s, not 27 (the first call is not waited for)").isBetween(24.8, 27.5);
        assertThat(lost.poll(8, TimeUnit.SECONDS)).as("the first call finished later and was billed: recorded once").isNotNull();
        assertThat(lost.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }
}

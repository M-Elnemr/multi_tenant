package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A call that hangs while a customer waits gets a twin, and the first good answer wins; background calls and quick calls are never doubled. */
class OpenAiProviderHedgeTest {
    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile long firstDelayMs = 0;
    long savedHedge;

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        OpenAiProvider.HEDGE_MS = 600;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            if (n == 1 && firstDelayMs > 0) try { Thread.sleep(firstDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] out = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer " + n + "\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            try {
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            } catch (java.io.IOException ignored) { /* the client dropped it */ }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; }

    OpenAiProvider provider() { return new OpenAiProvider("glm", "GLM", "k", "m", "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, ""); }

    @Test
    void aStuckCallWhileACustomerWaitsIsAnsweredByItsTwin() {
        firstDelayMs = 15_000;
        AiBudget.start(45_000);
        long t = System.nanoTime();
        Turn turn = provider().next("system", List.of(Turn.user("hi")), List.of());
        long took = (System.nanoTime() - t) / 1_000_000;
        assertThat(turn.text()).isEqualTo("answer 2");
        assertThat(took).isLessThan(5_000);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aQuickCallIsSentOnce() {
        AiBudget.start(45_000);
        assertThat(provider().next("system", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("answer 1");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void backgroundWorkIsNeverDoubled() {
        firstDelayMs = 1_500;   // slower than the hedge, but no customer is waiting
        assertThat(provider().next("system", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("answer 1");
        assertThat(calls.get()).isEqualTo(1);
    }
}

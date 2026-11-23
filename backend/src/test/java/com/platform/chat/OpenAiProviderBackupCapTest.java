package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A call to a model that has a backup (DR-R) is capped, keeps the backup's share of the reply budget and is never sent twice to the same key; without a backup nothing changed. */
class OpenAiProviderBackupCapTest {
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    long savedHedge;

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            requests.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            try { Thread.sleep(9_000); } catch (InterruptedException ignored) { }   // a hung provider
            try { ex.sendResponseHeaders(500, -1); } catch (java.io.IOException gone) { /* the caller timed out */ }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); OpenAiProvider.HEDGE_MS = savedHedge; AiBudget.clear(); }

    OpenAiProvider glm() { return new OpenAiProvider("glm", "GLM", "k", "m", "", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 100, true, "", 0); }

    @Test
    void withABackupTheHungCallEndsBeforeTheBackupsShareAndIsNotSentTwice() {
        OpenAiProvider.HEDGE_MS = 1_000;   // the old behaviour would send the same-key twin after 1 s and wait HEDGE_MS + 12 s
        AiBudget.start(7_000);
        AiBudget.hasBackup(2_000);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> glm().next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("budget 7 s - 1 s margin - 2 s for the backup = 4 s").isBetween(3_000L, 5_000L);
        assertThat(requests.get()).as("no same-key twin when a backup exists").isEqualTo(1);
        assertThat(AiBudget.hasLeft(1_500)).as("the backup still has its time").isTrue();
    }

    @Test
    void withoutABackupTheTwinHedgeStillFires() {
        OpenAiProvider.HEDGE_MS = 1_000;
        AiBudget.start(30_000);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> glm().next("s", List.of(Turn.user("x")), List.of())).isInstanceOf(AiUnavailableException.class);
        assertThat(requests.get()).as("first request and its twin (a keyed provider also retries a 5xx)").isGreaterThanOrEqualTo(2);
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(8_000L);
    }
}

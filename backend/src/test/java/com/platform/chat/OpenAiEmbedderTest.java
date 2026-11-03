package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A customer never waits long on the embeddings provider: one quick try within the reply's time, then searches go by words for a while. The index itself is patient. */
class OpenAiEmbedderTest {
    private HttpServer server;
    private volatile long delayMs = 0;
    private volatile int status = 200;

    private OpenAiEmbedder start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] b = (status == 200 ? "{\"data\":[{\"index\":0,\"embedding\":[3,4]}]}" : "{\"error\":\"no\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return new OpenAiEmbedder("k", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "m", 2, 10);
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); AiBudget.clear(); }

    @Test
    void aQueryIsUnitLengthAndQuick() throws Exception {
        var e = start();
        assertThat(e.embedQuery("سماعة")).containsExactly(0.6f, 0.8f);
        assertThat(e.embed(List.of("a"))[0]).containsExactly(0.6f, 0.8f);
    }

    @Test
    void aSlowProviderCostsOneShortWaitThenSearchesGoByWords() throws Exception {
        var e = start();
        delayMs = 10_000;
        long t = System.nanoTime();
        assertThatThrownBy(() -> e.embedQuery("سماعة")).isInstanceOf(IllegalStateException.class);
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(OpenAiEmbedder.QUERY_TIMEOUT_MS + 1_500);
        assertThat(e.enabled()).isFalse();   // resting: the next replies do not wait on it again
    }

    @Test
    void aReplyWithLittleTimeLeftDoesNotCallAtAll() throws Exception {
        var e = start();
        AiBudget.start(2_000);
        long t = System.nanoTime();
        assertThatThrownBy(() -> e.embedQuery("سماعة")).hasMessageContaining("No time left");
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(200);
        assertThat(e.enabled()).isTrue();   // nothing failed: not resting
    }

    @Test
    void aRefusalRestsLonger() throws Exception {
        var e = start();
        status = 403;
        assertThatThrownBy(() -> e.embedQuery("سماعة")).hasMessageContaining("403");
        assertThat(e.enabled()).isFalse();
    }
}

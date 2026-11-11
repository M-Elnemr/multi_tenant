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
    private volatile int failFirst = 0;   // the first N requests answer 503, then the normal status
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastAuth = "none";

    private OpenAiEmbedder start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            lastAuth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            int code = requests.incrementAndGet() <= failFirst ? 503 : status;
            byte[] b = (code == 200 ? "{\"data\":[{\"index\":0,\"embedding\":[3,4]}]}" : "{\"error\":\"no\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
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
    void aSlowProviderCostsOneBoundedWaitAndOneFailedQueryDoesNotSwitchSearchOffForEveryone() throws Exception {
        var e = start();
        delayMs = 10_000;
        long t = System.nanoTime();
        assertThatThrownBy(() -> e.embedQuery("سماعة")).isInstanceOf(IllegalStateException.class);
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(OpenAiEmbedder.QUERY_TIMEOUT_MS + 1_500);   // the retry is inside the same bounded wait
        assertThat(e.enabled()).isTrue();   // one failed query is not an outage: the next customer is not sent to words-only search
        assertThat(e.health()).containsEntry("failedInARow", 1).containsEntry("queriesFailed", 1L);
    }

    @Test
    void aTransientFailureIsRetriedInsideTheSameWait() throws Exception {
        var e = start();
        failFirst = 1;   // the first request answers 503: the retry succeeds
        assertThat(e.embedQuery("سماعة")).containsExactly(0.6f, 0.8f);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(e.health()).containsEntry("recoveredByRetry", 1L).containsEntry("queriesFailed", 0L).containsEntry("failedInARow", 0);
    }

    @Test
    void failedQueriesInARowRestSearchByMeaningAndASuccessStartsOver() throws Exception {
        var e = start();
        status = 503;
        for (int i = 0; i < OpenAiEmbedder.REST_AFTER_FAILED_QUERIES - 1; i++) assertThatThrownBy(() -> e.embedQuery("سماعة")).hasMessageContaining("503");
        assertThat(e.enabled()).isTrue();   // two in a row: still trying
        status = 200;
        assertThat(e.embedQuery("سماعة")).isNotNull();   // a success: the count starts over
        assertThat(e.health()).containsEntry("failedInARow", 0);
        status = 503;
        for (int i = 0; i < OpenAiEmbedder.REST_AFTER_FAILED_QUERIES; i++) assertThatThrownBy(() -> e.embedQuery("سماعة")).isInstanceOf(IllegalStateException.class);
        assertThat(e.enabled()).isFalse();   // three in a row: it rests, for everyone
        assertThat(e.configured()).isTrue();   // but it is still set up: the seller says "unavailable" instead of hiding the tool
        assertThat((Long) e.health().get("restingSeconds")).isGreaterThan(0L);
        assertThat(((java.util.Map<?, ?>) e.health().get("failuresByKind")).get("http-503")).isNotNull();
    }

    @Test
    void healthReportsLatencyAndLastError() throws Exception {
        var e = start();
        e.embedQuery("سماعة");
        status = 503;
        assertThatThrownBy(() -> e.embedQuery("سماعة")).isInstanceOf(IllegalStateException.class);
        var h = e.health();
        assertThat(h).containsEntry("queries", 2L).containsEntry("queriesOk", 1L).containsEntry("queriesFailed", 1L);
        assertThat(new java.util.TreeSet<Object>(((java.util.Map<?, ?>) h.get("latencyMs")).keySet())).containsExactlyInAnyOrder("avg", "median", "p95", "max");
        assertThat(String.valueOf(h.get("lastError"))).contains("503");
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

    @Test
    void aRejectedTextIsNotAnOutageAndDoesNotRestSearchForEveryone() throws Exception {
        var e = start();
        status = 400;
        for (int i = 0; i < OpenAiEmbedder.REST_AFTER_FAILED_QUERIES; i++) assertThatThrownBy(() -> e.embedQuery("سماعة")).hasMessageContaining("400");
        assertThat(requests.get()).as("a 400 is not retried").isEqualTo(OpenAiEmbedder.REST_AFTER_FAILED_QUERIES);
        assertThat(e.enabled()).as("a bad request for one text must not switch search off for all customers").isTrue();
        assertThat(e.health()).containsEntry("failedInARow", 0);
    }

    @Test
    void failuresFarApartAreNotInARow() throws Exception {
        var e = start();
        e.streakWindowMs = 50;
        status = 503;
        for (int i = 0; i < OpenAiEmbedder.REST_AFTER_FAILED_QUERIES; i++) {
            assertThatThrownBy(() -> e.embedQuery("سماعة")).isInstanceOf(IllegalStateException.class);
            Thread.sleep(120);   // longer than the window
        }
        assertThat(e.enabled()).as("three failures spread out are three hiccups, not an outage").isTrue();
        assertThat(e.health()).containsEntry("failedInARow", 1);
    }

    @Test
    void aSlowConnectIsStillAnsweredWithinTheBoundedWait() throws Exception {
        var e = start();
        delayMs = 1_600;   // the old 1.5 s limits would have failed here; one try fits in the wait
        long t = System.nanoTime();
        assertThat(e.embedQuery("سماعة")).isNotNull();
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(OpenAiEmbedder.QUERY_TIMEOUT_MS);
    }

    @Test
    void theConnectionIsKeptWarmOnlyWhileCustomersAreSearchingAndNeverWithTheKey() throws Exception {
        var e = start();
        assertThat(e.warm()).as("no query yet: nothing to keep warm").isFalse();
        assertThat(requests.get()).isZero();
        e.embedQuery("سماعة");
        int before = requests.get();
        lastAuth = "none";
        assertThat(e.warm()).isTrue();
        assertThat(requests.get()).isEqualTo(before + 1);
        assertThat(lastAuth).as("the warm-up request carries no key").isEqualTo("null");
        assertThat(e.health()).containsEntry("queries", 1L);   // a warm-up is not a query
    }
}

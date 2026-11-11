package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Requests to a Hive model go over every key of the pool in turn, and a key that is told "too many requests" is left alone while the request goes on another. */
class OpenAiProviderKeyPoolTest {
    HttpServer server;
    final List<String> auth = new CopyOnWriteArrayList<>();
    volatile String busyKey = "";
    long savedHedge;

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            auth.add(a);
            ex.getRequestBody().readAllBytes();
            boolean busy = !busyKey.isEmpty() && a.endsWith(busyKey);
            byte[] out = (busy ? "{\"error\":\"too many\"}" : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(busy ? 429 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; }

    OpenAiProvider provider(HiveKeyPool pool) { return new OpenAiProvider("glm", "GLM", pool, "m", () -> "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, "", 0); }

    @Test
    void requestsAreSpreadOverTheKeys() {
        var p = provider(HiveKeyPool.of("key-AAAA", "key-BBBB", "key-CCCC"));
        for (int i = 0; i < 6; i++) assertThat(p.next("s", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("ok");
        assertThat(auth).hasSize(6);
        assertThat(auth.stream().distinct().count()).isEqualTo(3);
        assertThat(auth.stream().filter(a -> a.endsWith("key-AAAA")).count()).isEqualTo(2);
    }

    @Test
    void aBusyKeyIsRetriedOnAnotherKeyAndThenLeftAlone() {
        busyKey = "key-AAAA";
        var pool = HiveKeyPool.of("key-AAAA", "key-BBBB");
        var p = provider(pool);
        for (int i = 0; i < 4; i++) assertThat(p.next("s", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("ok");
        assertThat(auth.stream().filter(a -> a.endsWith("key-AAAA")).count()).as("the busy key is asked once, then it rests").isEqualTo(1);
        assertThat(pool.view().stream().filter(m -> "env-1".equals(m.get("id"))).findFirst().orElseThrow().get("failures")).isEqualTo(1L);
    }

    @Test
    void theTwinOfASlowCallUsesAnotherKey() {
        OpenAiProvider.HEDGE_MS = 300;
        // the first request (whichever key it takes) is held 1.5 s: the twin goes out at 0.3 s and must carry the OTHER key
        server.removeContext("/v1/chat/completions");
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        server.createContext("/v1/chat/completions", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            if (first.getAndSet(false)) try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        AiBudget.start(45_000);
        var p = provider(HiveKeyPool.of("key-AAAA", "key-BBBB"));
        assertThat(p.next("s", List.of(Turn.user("hi")), List.of()).text()).isEqualTo("ok");
        assertThat(auth).hasSize(2);
        assertThat(auth.get(0)).isNotEqualTo(auth.get(1));
    }

    @Test
    void withNoKeyTheModelIsNotAvailable() {
        var p = provider(HiveKeyPool.of());
        assertThat(p.available()).isFalse();
    }

    @Test
    void eachKeyIsHeldToFiveRequestsASecondEvenWhenManyThreadsCallAtOnce() throws Exception {
        server.removeContext("/v1/chat/completions");
        var arrivals = new java.util.concurrent.ConcurrentHashMap<String, List<Long>>();
        server.createContext("/v1/chat/completions", ex -> {
            arrivals.computeIfAbsent(ex.getRequestHeaders().getFirst("Authorization"), k -> new CopyOnWriteArrayList<>()).add(System.nanoTime());
            ex.getRequestBody().readAllBytes();
            byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        var p = new OpenAiProvider("glm", "GLM", HiveKeyPool.of("rps-key-AAAA", "rps-key-BBBB", "rps-key-CCCC"), "m", () -> "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, "", 5);
        var ex = java.util.concurrent.Executors.newFixedThreadPool(18);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 36; i++) futures.add(ex.submit(() -> p.next("s", List.of(Turn.user("hi")), List.of()).text()));
        for (var f : futures) assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("ok");
        double seconds = (System.nanoTime() - t0) / 1e9;
        ex.shutdown();
        assertThat(arrivals).hasSize(3);
        for (var e : arrivals.entrySet()) {
            List<Long> ts = new java.util.ArrayList<>(e.getValue());
            java.util.Collections.sort(ts);
            int worst = 0;
            for (int i = 0; i < ts.size(); i++) { int c = 0; for (int j = i; j < ts.size() && ts.get(j) - ts.get(i) < 950_000_000L; j++) c++; worst = Math.max(worst, c); }
            assertThat(worst).as("most requests of " + e.getKey() + " inside any 0.95 s").isLessThanOrEqualTo(5);
        }
        assertThat(seconds).as("36 requests over 3 keys at 5 a second each take about 2.4 s, not 7 (one key) and not 0").isBetween(1.5, 4.5);
    }
}

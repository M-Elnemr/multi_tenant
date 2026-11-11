package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.ToolDef;
import com.platform.chat.AiProvider.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Production audit of the hedge: when BOTH twins come back with a tool call, the reply still gets exactly one turn (so the tool runs once, in ChatService, after the winner), the loser's
 * tokens are recorded exactly once on the right shop, and many replies hedging at the same time never mix their answers or their billing.
 */
class OpenAiProviderHedgeAuditTest {
    HttpServer server;
    final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();   // requests per customer marker: the first one is the slow one
    final List<String> bodies = new CopyOnWriteArrayList<>();
    long savedHedge;
    static final List<ToolDef> TOOLS = List.of(new ToolDef("search_products", "search", Map.of("type", "OBJECT", "properties", Map.of())));

    @BeforeEach
    void start() throws Exception {
        savedHedge = OpenAiProvider.HEDGE_MS;
        OpenAiProvider.HEDGE_MS = 300;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String marker = body.replaceAll("(?s).*(mk-\\d+).*", "$1");
            int n = seen.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
            if (n == 1) try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }   // the first request of each reply hangs
            String out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_" + marker + "_" + n + "\",\"type\":\"function\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{\\\"query\\\":\\\"" + marker + "\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            try { ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); } catch (java.io.IOException ignored) { /* the client moved on */ }
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); AiBudget.clear(); OpenAiProvider.HEDGE_MS = savedHedge; OpenAiProvider.LOST_TWIN_SINK = null; }

    OpenAiProvider provider() { return new OpenAiProvider("glm", "GLM", HiveKeyPool.of("key-AAAA", "key-BBBB"), "m", () -> "low", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 500, true, "", 0); }

    record Lost(UUID tenant, AiProvider.Usage usage) {}

    @Test
    void whenBothTwinsAnswerWithAToolCallTheReplyGetsOneTurnAndTheLoserIsRecordedOnce() throws Exception {
        var lost = new LinkedBlockingQueue<Lost>();
        OpenAiProvider.LOST_TWIN_SINK = (tenant, model, usage) -> lost.add(new Lost(tenant, usage));
        UUID shop = UUID.randomUUID();
        AiBudget.start(45_000);
        AiBudget.forTenant(shop);
        Turn t = provider().next("s", List.of(Turn.user("mk-1")), TOOLS);
        assertThat(t.calls()).as("one turn, one tool call: the tool runs once").hasSize(1);
        assertThat(((com.fasterxml.jackson.databind.JsonNode) t.raw()).path("tool_calls").get(0).path("id").asText()).as("the twin that answered first (request 2) is the one replayed").isEqualTo("call_mk-1_2");
        Lost l = lost.poll(10, TimeUnit.SECONDS);
        assertThat(l).isNotNull();
        assertThat(l.tenant()).isEqualTo(shop);
        assertThat(l.usage().input()).isEqualTo(7);
        assertThat(lost.poll(800, TimeUnit.MILLISECONDS)).as("recorded exactly once").isNull();
        assertThat(bodies).as("the twin is the same request: same messages, no extra tool results").hasSize(2);
        assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
    }

    @Test
    void manyRepliesHedgingAtTheSameTimeKeepTheirAnswersAndTheirBillingApart() throws Exception {
        var lost = new LinkedBlockingQueue<Lost>();
        OpenAiProvider.LOST_TWIN_SINK = (tenant, model, usage) -> lost.add(new Lost(tenant, usage));
        OpenAiProvider p = provider();
        int n = 8;
        List<UUID> shops = new ArrayList<>();
        for (int i = 0; i < n; i++) shops.add(UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<String>> got = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            got.add(pool.submit(() -> {
                AiBudget.start(45_000);
                AiBudget.forTenant(shops.get(k));
                try {
                    Turn t = p.next("s", List.of(Turn.user("mk-" + (100 + k))), TOOLS);
                    return t.calls().size() + ":" + t.calls().get(0).args().get("query");
                } finally { AiBudget.clear(); }
            }));
        }
        for (int i = 0; i < n; i++) assertThat(got.get(i).get(20, TimeUnit.SECONDS)).isEqualTo("1:mk-" + (100 + i));   // each reply got its own single answer
        List<UUID> billed = new ArrayList<>();
        for (int i = 0; i < n; i++) { Lost l = lost.poll(10, TimeUnit.SECONDS); assertThat(l).isNotNull(); billed.add(l.tenant()); }
        assertThat(billed).as("one loser per reply, each on its own shop").containsExactlyInAnyOrderElementsOf(shops);
        assertThat(lost.poll(800, TimeUnit.MILLISECONDS)).isNull();
        pool.shutdownNow();
    }
}

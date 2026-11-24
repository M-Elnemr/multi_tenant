package com.platform.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiTelemetryTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void off() { AiTelemetry.configure(false, "", "", "", "", false, 10, ""); }

    @Test
    void redactsPhonesEmailsAndKeys() {
        String r = AiTelemetry.redact("call me 01012345678 or +20 100 123 4567, mail a.b@x.com, key sk-abcdefghijklmnop1234 Bearer abcdefghijklmnop1234");
        assertFalse(r.contains("0101234"), r);
        assertFalse(r.contains("a.b@x.com"), r);
        assertFalse(r.contains("sk-abcdef"), r);
        assertFalse(r.contains("Bearer abcdef"), r);
        assertTrue(r.contains("[phone]") && r.contains("[email]") && r.contains("[secret]"), r);
    }

    @Test
    void offByDefaultAndCallsAreNoOps() {
        assertFalse(AiTelemetry.configure(false, "http://x", "a", "b", "", false, 10, ""));
        assertFalse(AiTelemetry.configure(true, "", "a", "b", "", false, 10, ""));   // incomplete: stays off
        AiTelemetry.generation("glm", "m", 1, 2, null, 200, null, null, null);   // must not throw
        AiTelemetry.endReply("TEST", "x", 1);
    }

    @Test
    void sendsOtlpWithHashedIdsAndNoContentByDefault() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<String> auth = new LinkedBlockingQueue<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/public/otel/v1/traces", ex -> {
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));   // the header first: the test reads it right after the body shows up
            got.add(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1); ex.close();
        });
        srv.start();
        try {
            assertTrue(AiTelemetry.configure(true, "http://127.0.0.1:" + srv.getAddress().getPort(), "pk", "sk", "salt", false, 100, "test"));
            UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
            AiTelemetry.beginReply(tenant, conv);
            AiTelemetry.generation("glm", "zai-org/glm-5.3-flash", 1000, 4100, new AiProvider.Usage(1200, 80, 900, 0), 200, null, "PROMPT", "ANSWER 01012345678");
            AiTelemetry.endReply("WHATSAPP", "total=4200", 900);
            String body = got.poll(20, TimeUnit.SECONDS);
            assertNotNull(body, "nothing delivered");
            assertTrue(auth.poll().startsWith("Basic "));
            assertFalse(body.contains(tenant.toString()) || body.contains(conv.toString()), "raw ids leaked");
            assertFalse(body.contains("PROMPT") || body.contains("ANSWER"), "content sent while capture is off");
            JsonNode spans = json.readTree(body).at("/resourceSpans/0/scopeSpans/0/spans");
            assertTrue(spans.size() >= 1);
            assertTrue(body.contains("gen_ai.request.model") && body.contains("usage_details") && body.contains("cache_read_input_tokens"));
        } finally { srv.stop(0); }
    }

    @Test
    void unreachableEndpointNeverBlocksOrThrows() {
        assertTrue(AiTelemetry.configure(true, "http://127.0.0.1:1", "pk", "sk", "", false, 10, ""));
        long t0 = System.nanoTime();
        AiTelemetry.beginReply(UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 5000; i++) AiTelemetry.generation("glm", "m", 1, 2, null, 200, null, null, null);   // far more than the queue holds
        AiTelemetry.endReply("TEST", "x", 1);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500, "the reply path waited on telemetry");
        assertTrue(AiTelemetry.dropped() > 0, "a full queue must drop, not block");
    }

    @Test
    void aProviderFailureAndTheFallbackAreVisibleWithTheirCategory() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/public/otel/v1/traces", ex -> { got.add(new String(ex.getRequestBody().readAllBytes())); ex.sendResponseHeaders(200, -1); ex.close(); });
        srv.start();
        try {
            assertTrue(AiTelemetry.configure(true, "http://127.0.0.1:" + srv.getAddress().getPort(), "pk", "sk", "salt", false, 100, ""));
            AiTelemetry.beginReply(UUID.randomUUID(), UUID.randomUUID());
            AiTelemetry.generation("glm", "zai-org/glm-5.3-flash", 1000, 1900, null, 200, "OVERLOAD_MESSAGE: capacity notice", null, null);
            AiTelemetry.step("event", "provider-fallback:glm->qwen:OVERLOAD_MESSAGE", 1900, 1900, true);
            AiTelemetry.generation("qwen", "qwen3.7-flash", 1900, 3200, new AiProvider.Usage(900, 60, 0, 0), 200, null, null, null);
            AiTelemetry.endReply("WHATSAPP", "total=3300", 900);
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < 3 && all.indexOf("qwen3.7-flash") < 0; i++) { String b = got.poll(20, TimeUnit.SECONDS); if (b != null) all.append(b); }
            String body = all.toString();
            assertTrue(body.contains("OVERLOAD_MESSAGE: capacity notice") && body.contains("\"ERROR\""), "the failed GLM attempt is an ERROR span with its category");
            assertTrue(body.contains("provider-fallback:glm->qwen:OVERLOAD_MESSAGE"), "the fallback is its own event");
            assertTrue(body.contains("qwen3.7-flash") && body.contains("zai-org/glm-5.3-flash"), "both models are on the trace");
        } finally { srv.stop(0); }
    }
}

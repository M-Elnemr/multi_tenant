package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The metrics a reply produces: the inventory exists, every label is from a small closed set, and no id of a trace, shop, chat, customer or message is ever a label. */
class ReplyMetricsTest {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    java.util.function.Consumer<ReplyTrace> savedSink;
    static final Set<String> ALLOWED_TAGS = Set.of("channel", "source", "outcome", "provider", "role", "purpose", "kind", "category", "result", "from", "to", "tool", "reason", "verdict", "status", "via", "model", "pool", "check", "le");   // check = the name of one deterministic rule check (about 55 fixed names); le = the fixed histogram bucket bound

    @BeforeEach
    void add() { Metrics.addRegistry(registry); savedSink = ReplyTrace.sink; ReplyTrace.sink = t -> { }; ReplyTrace.enabled = true; }

    @AfterEach
    void remove() { Metrics.removeRegistry(registry); ReplyTrace.sink = savedSink; }

    @Test
    void aReplyProducesTheMetricInventoryWithOnlyLowCardinalityLabels() throws Exception {
        UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
        ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(tenant, conv, false, false);
        ReplyTrace.Call call = ReplyTrace.callStart("glm", "glm-5");
        var attempt = call.attempt(false);
        attempt.finish(0, ProviderFailure.TIMEOUT, true, false, null);
        call.hedgeLaunched(15_000);
        call.hedgeRequest("twin", System.nanoTime() - 1_000_000L, 200, null, false, null);
        call.end(false, ProviderFailure.TIMEOUT, null);
        ReplyTrace.fallbackStarted("glm", "qwen", ProviderFailure.TIMEOUT, 27_000);
        ReplyTrace.fallbackCompleted("glm", "qwen", System.nanoTime(), true);
        ReplyTrace.tool("search_products", System.nanoTime(), true, 0);
        ReplyTrace.ruleChecks(() -> ReplyTrace.rewrite("rule_check", () -> "x"), "draft");
        ReplyTrace.review("approved", System.nanoTime(), "glm", "", false, null);
        ReplyTrace.finish(false, null);
        ReplyTrace.outbound("WHATSAPP", "cloud", "text", () -> "mid");
        try { ReplyTrace.outbound("INSTAGRAM", "graph", "text", () -> { throw new IllegalStateException(); }); } catch (IllegalStateException expected) { /* handled by the channel */ }
        ReplyTrace.inboundEnd();

        for (String name : List.of("elmanassa.provider.call", "elmanassa.provider.attempt", "elmanassa.provider.attempts", "elmanassa.provider.failures", "elmanassa.provider.timeouts", "elmanassa.provider.hedges",
                "elmanassa.reply.fallbacks", "elmanassa.reply.fallback_duration", "elmanassa.tool.duration", "elmanassa.rulechecks.duration", "elmanassa.rulechecks.deterministic", "elmanassa.rewrite.duration",
                "elmanassa.rewrite.rounds", "elmanassa.review.duration", "elmanassa.review.verdicts", "elmanassa.outbound.duration", "elmanassa.outbound.failures", "elmanassa.reply.latency"))
            assertThat(registry.find(name).meters()).as(name).isNotEmpty();

        for (Meter m : registry.getMeters()) {
            if (!m.getId().getName().startsWith("elmanassa.")) continue;
            for (var tag : m.getId().getTags()) {
                assertThat(ALLOWED_TAGS).as(m.getId().getName() + " tag key " + tag.getKey()).contains(tag.getKey());
                assertThat(tag.getValue()).as(m.getId().getName() + " " + tag.getKey()).hasSizeLessThanOrEqualTo(24).doesNotContain(tenant.toString()).doesNotContain(conv.toString()).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}-.*");
            }
        }
        assertThat(registry.find("elmanassa.provider.timeouts").tag("provider", "glm").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("elmanassa.reply.fallbacks").tag("from", "glm").tag("to", "qwen").tag("category", "timeout").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("elmanassa.review.verdicts").tag("verdict", "approved").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("elmanassa.outbound.failures").tag("channel", "INSTAGRAM").tag("via", "graph").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anUnknownChannelOrCodeBecomesOtherAndExecutorsAreGauges() {
        assertThat(ReplyMetrics.channel("someone's chat 0101234")).isEqualTo("other");
        assertThat(ReplyMetrics.code("A Very Long Provider Name That Is Not A Code")).isEqualTo("other");
        assertThat(ReplyMetrics.code("GLM-5")).isEqualTo("glm_5");
        var pool = (ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(2);
        ReplyMetrics.pool("test_pool", pool);
        assertThat(registry.find("elmanassa.executor.active").tag("pool", "test_pool").gauge()).isNotNull();
        assertThat(registry.find("elmanassa.executor.queue").tag("pool", "test_pool").gauge().value()).isZero();
        pool.shutdown();
    }
}

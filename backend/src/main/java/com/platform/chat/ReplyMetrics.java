package com.platform.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.ToDoubleFunction;

/**
 * Reply-latency metrics on the global Micrometer registry (Spring Boot binds its Prometheus registry to it; with no registry every call does nothing). Every label is a small closed set:
 * a model code, a channel, a tool name, a verdict, an outcome. NEVER a trace, shop, chat, customer, product or message id. Every method swallows its own errors.
 * Histograms use fixed second buckets so a stall (15, 20, 27, 30, 45 s) is visible as its own bucket.
 */
final class ReplyMetrics {
    private static final Duration[] BUCKETS = {Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(8),
            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(27), Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofSeconds(60)};
    private static final Set<String> CHANNELS = Set.of("WEB", "WHATSAPP", "MESSENGER", "INSTAGRAM", "TEST");

    private ReplyMetrics() {}

    /** A label value restricted to lowercase letters, digits and underscore (at most 24); anything else is «other». */
    static String code(String v) {
        if (v == null || v.isEmpty()) return "none";
        String x = v.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        return x.length() > 24 ? "other" : x;
    }

    static String channel(String c) { return c != null && CHANNELS.contains(c) ? c : "other"; }

    static void timer(String name, long ms, String... tags) {
        if (!ReplyTrace.enabled) return;   // AI_TRACE=false turns every elmanassa.* metric off with the traces
        try { Timer.builder(name).tags(tags).serviceLevelObjectives(BUCKETS).register(Metrics.globalRegistry).record(Duration.ofMillis(Math.max(0, ms))); } catch (RuntimeException ignored) { /* metrics never fail a reply */ }
    }

    static void count(String name, String... tags) {
        if (!ReplyTrace.enabled) return;
        try { Counter.builder(name).tags(tags).register(Metrics.globalRegistry).increment(); } catch (RuntimeException ignored) { /* see timer */ }
    }

    /** The end-to-end latency of a finished reply (from the first webhook line, or from the start of processing when it did not come through the burst holder). */
    static void reply(ReplyTrace t) {
        try {
            Object e2e = t.summary().get("end_to_end_ms");
            timer("elmanassa.reply.latency", e2e instanceof Number n ? n.longValue() : t.replyMs(), "channel", channel(t.channel), "source", code(t.source()), "outcome", t.failed() ? "failed" : "completed");
        } catch (RuntimeException ignored) { /* see timer */ }
    }

    /** Active threads and queue depth of a worker pool (the Meta pool of 4 and the Evolution pool of 2 threads); a pool that is not a ThreadPoolExecutor is skipped. */
    static void pool(String name, Object executor) {
        if (!ReplyTrace.enabled) return;
        try {
            if (!(executor instanceof ThreadPoolExecutor p)) return;
            Gauge.builder("elmanassa.executor.active", p, ThreadPoolExecutor::getActiveCount).tag("pool", name).register(Metrics.globalRegistry);
            Gauge.builder("elmanassa.executor.queue", p, x -> x.getQueue().size()).tag("pool", name).register(Metrics.globalRegistry);
        } catch (RuntimeException ignored) { /* see timer */ }
    }

    static <T> void gauge(String name, T obj, ToDoubleFunction<T> f, String... tags) {
        if (!ReplyTrace.enabled) return;
        try { Gauge.builder(name, obj, f).tags(tags).register(Metrics.globalRegistry); } catch (RuntimeException ignored) { /* see timer */ }
    }
}

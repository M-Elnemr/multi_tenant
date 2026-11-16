package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the instrumentation itself costs per reply: a typical reply (3 model calls, 2 tools, review, rule checks, rewrite, 2 sends) traced with a metrics registry attached. Synthetic, on the machine running the test. */
class ReplyTraceOverheadTest {
    @Test
    void aTypicalRepliesTraceCostsWellUnderAMillisecondOnTheReplyThread() {
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        var saved = ReplyTrace.sink;
        ReplyTrace.sink = t -> { };   // the writer thread's work is not the reply thread's
        ReplyTrace.enabled = true;
        try {
            for (int i = 0; i < 3_000; i++) typical();   // warm up the JIT and the meters
            int n = 5_000;
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) typical();
            double micros = (System.nanoTime() - start) / 1_000.0 / n;
            System.out.printf("ReplyTrace overhead: %.1f microseconds per typical reply (%d replies)%n", micros, n);
            assertThat(micros).isLessThan(1_000.0);
        } finally { ReplyTrace.sink = saved; Metrics.removeRegistry(registry); }
    }

    static void typical() {
        ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        for (int c = 0; c < 3; c++) {
            var call = ReplyTrace.callStart("glm", "glm-5");
            var a = call.attempt(false);
            a.finish(200, null, false, true, new AiProvider.Usage(2000, 100, 1500));
            call.end(true, null, new AiProvider.Usage(2000, 100, 1500));
        }
        ReplyTrace.tool("search_products", System.nanoTime(), true, 0);
        ReplyTrace.tool("show_products", System.nanoTime(), true, 1);
        ReplyTrace.ruleChecks(() -> ReplyTrace.rewrite("rule_check", () -> "x"), "d");
        ReplyTrace.review("approved", System.nanoTime(), "glm", "", false, null);
        for (int k = 0; k < 8; k++) ReplyTrace.span("phase", System.nanoTime(), "name", "p");
        ReplyTrace.finish(false, null);
    }
}

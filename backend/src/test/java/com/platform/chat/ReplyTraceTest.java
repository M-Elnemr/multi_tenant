package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The per-message timeline: ids, tenants, the burst holder's waits, outbound acceptance, rule checks and rewrites, review verdicts, privacy and failure isolation. Synthetic: no model, no network. */
class ReplyTraceTest {
    final List<ReplyTrace> done = new CopyOnWriteArrayList<>();
    Consumer<ReplyTrace> savedSink;

    @BeforeEach
    void install() { savedSink = ReplyTrace.sink; ReplyTrace.sink = done::add; ReplyTrace.enabled = true; }

    @AfterEach
    void restore() { ReplyTrace.sink = savedSink; ReplyTrace.role(null); ReplyTrace.purpose(null); }

    static ReplyTrace.Event first(ReplyTrace t, String name) { return t.snapshot().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null); }

    static List<ReplyTrace.Event> all(ReplyTrace t, String name) { return t.snapshot().stream().filter(e -> e.name().equals(name)).toList(); }

    ReplyTrace reply(UUID tenant, String channel, Runnable body) {
        ReplyTrace t = ReplyTrace.begin(channel);
        ReplyTrace.identify(tenant, UUID.randomUUID(), false, false);
        body.run();
        ReplyTrace.finish(false, null);
        return t;
    }

    // ---- correlation ---------------------------------------------------------------------------------------------------------

    @Test
    void everyReplyGetsItsOwnTraceAndConcurrentRepliesNeverMix() throws Exception {
        int n = 12;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        List<UUID> tenants = new ArrayList<>();
        for (int i = 0; i < n; i++) tenants.add(UUID.randomUUID());
        for (int i = 0; i < n; i++) {
            int k = i;
            pool.execute(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                reply(tenants.get(k), "WEB", () -> { for (int j = 0; j < 20; j++) ReplyTrace.instant("note", "lines", k); });
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(done).hasSize(n);
        assertThat(done.stream().map(t -> t.id).distinct()).hasSize(n);
        for (ReplyTrace t : done) {
            var owners = t.snapshot().stream().filter(e -> e.name().equals("note")).map(e -> e.a().get("lines")).distinct().toList();
            assertThat(owners).hasSize(1);   // 20 events, all from the one thread that owned this trace
            assertThat(tenants.get(((Number) owners.get(0)).intValue())).isEqualTo(t.tenantId());   // and filed under that thread's shop
        }
    }

    @Test
    void anAsyncThreadReportsIntoTheTraceItsCallBelongsToNotItsOwnThreads() throws Exception {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.Call call = ReplyTrace.callStart("glm", "m");
        var other = Executors.newSingleThreadExecutor();
        other.submit(() -> call.hedgeRequest("twin", System.nanoTime() - 5_000_000L, 200, null, true, null)).get();   // another thread, no trace of its own
        other.shutdown();
        ReplyTrace.finish(false, null);
        assertThat(all(t, "provider_hedge_request")).hasSize(1);
        assertThat(done).hasSize(1);
    }

    @Test
    void aReplyWithNoShopIsNeverPersistedButStillMeasured() {
        ReplyTrace.begin("WEB");
        ReplyTrace.finish(true, null);   // failed before the conversation was found
        assertThat(done).isEmpty();
    }

    // ---- burst and queue ---------------------------------------------------------------------------------------------------------

    @Test
    void theBurstWindowAndTheWaitForAWorkerAreMeasuredWithoutChangingTheBurst() throws Exception {
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        var worker = Executors.newSingleThreadExecutor();
        var busy = new CountDownLatch(1);
        worker.execute(() -> { busy.countDown(); try { Thread.sleep(400); } catch (InterruptedException ignored) { /* test */ } });   // the only worker is occupied
        busy.await();
        InboundBurst burst = new InboundBurst(timers, worker, 200, 5_000);
        List<String> texts = new CopyOnWriteArrayList<>();
        UUID tenant = UUID.randomUUID();
        var fired = new CountDownLatch(1);
        java.util.function.BiConsumer<String, List<Object>> fire = (text, metas) -> {
            texts.add(text);
            ReplyTrace.begin("WHATSAPP");
            ReplyTrace.identify(tenant, UUID.randomUUID(), false, false);
            ReplyTrace.finish(false, null);   // the channel would now send; the holder's worker completes the trace
            fired.countDown();
        };
        burst.add("k", "hello", null, fire);
        Thread.sleep(60);
        burst.add("k", "size M", null, fire);
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        timers.shutdown(); worker.shutdown();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(texts).containsExactly("hello\nsize M");   // the lines are joined in order, once: behaviour is unchanged
        assertThat(done).hasSize(1);
        ReplyTrace t = done.get(0);
        var burstWait = first(t, "burst_wait");
        var queueWait = first(t, "queue_wait");
        assertThat(burstWait.d()).isBetween(240L, 700L);                  // 60 ms between the lines + the 200 ms quiet window
        assertThat(burstWait.a().get("lines")).isEqualTo(2);
        assertThat(queueWait.d()).isBetween(100L, 400L);                  // the worker was busy for 400 ms; the burst flushed at about 260 ms
        assertThat(first(t, "webhook_received").t()).isZero();
        assertThat(first(t, "processing_started").t()).isGreaterThanOrEqualTo(burstWait.d() + queueWait.d());
        assertThat(t.summary().get("burst_ms")).isEqualTo(burstWait.d());
        assertThat(t.summary().get("queue_ms")).isEqualTo(queueWait.d());
    }

    // ---- outbound -----------------------------------------------------------------------------------------------------------------

    @Test
    void outboundSendsAreTimedPerChannelAndAnAcceptedSendIsNeverCalledDelivered() throws Exception {
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        var worker = Executors.newSingleThreadExecutor();
        InboundBurst burst = new InboundBurst(timers, worker, 20, 1_000);
        var fired = new CountDownLatch(1);
        burst.add("k", "hi", null, (text, metas) -> {
            ReplyTrace.begin("WHATSAPP");
            ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
            ReplyTrace.finish(false, null);
            ReplyTrace.outbound("WHATSAPP", "cloud", "text", () -> { sleep(60); return "mid"; });
            try { ReplyTrace.outbound("INSTAGRAM", "graph", "card", () -> { throw new IllegalStateException("platform refused"); }); } catch (IllegalStateException expected) { /* the channel handles it */ }
            ReplyTrace.outbound("WHATSAPP", "evolution", "card", () -> "FAILED", r -> !"FAILED".equals(r));
            fired.countDown();
        });
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        timers.shutdown(); worker.shutdown();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        ReplyTrace t = done.get(0);
        var ok = first(t, "outbound_completed");
        assertThat(ok.a()).containsEntry("channel", "WHATSAPP").containsEntry("via", "cloud").containsEntry("status", "accepted").containsEntry("confirmed", false);
        assertThat(ok.d()).isGreaterThanOrEqualTo(55L);
        var failed = all(t, "outbound_failed");
        assertThat(failed).hasSize(2);
        assertThat(failed.get(0).a()).containsEntry("channel", "INSTAGRAM").containsEntry("error", "IllegalStateException");
        assertThat(failed.get(1).a()).containsEntry("via", "evolution").containsEntry("error", "rejected");
        assertThat(t.summary().get("outbound_failures")).isEqualTo(2L);
        assertThat(t.snapshot().stream().map(ReplyTrace.Event::a).filter(a -> "delivered".equals(a.get("status")))).isEmpty();
    }

    // ---- lifecycle of a messaging reply without the burst holder ---------------------------------------------------------------------

    @Test
    void aMessagingReplyWaitsForItsOutboundSendsEvenWithoutTheBurstHolderAndAWebReplyDoesNot() {
        ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.finish(false, null);
        assertThat(done).isEmpty();   // not complete yet: the channel still sends
        ReplyTrace.outbound("WHATSAPP", "cloud", "text", () -> "mid");
        ReplyTrace.completeDeferred();
        assertThat(done).hasSize(1);
        assertThat(first(done.get(0), "outbound_completed")).isNotNull();

        done.clear();
        ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.finish(false, null);
        assertThat(done).hasSize(1);   // a website reply is complete when the model flow is
    }

    @Test
    void aTraceNoChannelCompletedIsSavedWhenTheNextReplyBeginsNotLost() {
        ReplyTrace.begin("INSTAGRAM");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.finish(false, null);
        assertThat(done).isEmpty();
        ReplyTrace.begin("WEB");   // the same thread starts another reply
        assertThat(done).hasSize(1);
        ReplyTrace.finish(false, null);
    }

    @Test
    void aFailedReplyIsCompleteAtOnceBecauseNothingWillBeSent() {
        ReplyTrace.begin("WHATSAPP");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.finish(true, null);
        assertThat(done).hasSize(1);
        assertThat(done.get(0).failed()).isTrue();
    }

    // ---- queue saturation ----------------------------------------------------------------------------------------------------------

    @Test
    void aFullWriterQueueDropsTracesAndCountsThemInsteadOfBlockingTheReply() {
        ReplyTraceStore store = new ReplyTraceStore(null, true, 5);   // never started: nothing drains the queue of 5
        List<ReplyTrace> traces = new ArrayList<>();
        for (int i = 0; i < 50; i++) { ReplyTrace t = ReplyTrace.begin("WEB"); ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false); traces.add(t); ReplyTrace.finish(false, null); }
        long start = System.nanoTime();
        for (ReplyTrace t : traces) store.offer(t);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(store.dropped()).isEqualTo(45L);   // 5 queued, 45 dropped
        assertThat(ms).as("offering 50 traces to a full queue never waits").isLessThan(500L);
    }

    // ---- tools, rule checks, rewrites ----------------------------------------------------------------------------------------------

    @Test
    void toolDurationsFailuresAndRewriteRoundsAreCountedAndDeterministicTimeIsSeparatedFromRewriteTime() {
        ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> {
            long a = System.nanoTime(); sleep(30); ReplyTrace.tool("search_products", a, true, 0);
            long b = System.nanoTime(); sleep(10); ReplyTrace.tool("prepare_order", b, false, 1);
            String out = ReplyTrace.ruleChecks(() -> {
                sleep(80);   // deterministic checks
                ReplyTrace.checkProblems(List.of("STALL", "VAGUE"));
                return ReplyTrace.rewrite("rule_check", () -> { sleep(150); return "better"; });   // one rewrite model loop
            }, "draft");
            assertThat(out).isEqualTo("better");
        });
        var tools = all(t, "tool");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).a()).containsEntry("tool", "search_products").containsEntry("ok", true).containsEntry("next_model_call", true);
        assertThat(tools.get(0).d()).isGreaterThanOrEqualTo(25L);
        assertThat(tools.get(1).a()).containsEntry("tool", "prepare_order").containsEntry("ok", false);
        var rc = first(t, "rule_checks");
        assertThat(rc.a()).containsEntry("problems", 2).containsEntry("rewrites", 1).containsEntry("kinds", "STALL:VAGUE").containsEntry("accepted_original", false);
        assertThat(((Number) rc.a().get("rewrite_ms")).longValue()).isGreaterThanOrEqualTo(140L);
        long det = ((Number) rc.a().get("deterministic_ms")).longValue();
        assertThat(det).isBetween(70L, 140L);                                   // the 80 ms of checks, not the 150 ms of rewrite
        assertThat(rc.d()).isGreaterThanOrEqualTo(det + 140L);
        assertThat(all(t, "rewrite")).hasSize(1);
        assertThat(t.summary()).containsEntry("rewrites", 1L).containsEntry("tools", 2L);
    }

    @Test
    void aCleanDraftIsRecordedAsAcceptedWithNoRewrite() {
        ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> ReplyTrace.ruleChecks(() -> "same", "same"));
        assertThat(first(t, "rule_checks").a()).containsEntry("accepted_original", true).containsEntry("rewrites", 0).containsEntry("problems", 0);
    }

    // ---- review ----------------------------------------------------------------------------------------------------------------------

    @Test
    void everyReviewVerdictIsRecordedWithItsDurationModelAndWhetherTheReplyChanged() {
        ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> {
            for (String v : List.of("approved", "corrected", "kept_original", "timeout", "error", "unknown", "skipped")) {
                long s = System.nanoTime(); sleep(2);
                ReplyTrace.review(v, s, "glm", "corrected".equals(v) ? "" : "x", "corrected".equals(v), new AiProvider.Usage(100, 7, 0));
            }
        });
        var reviews = all(t, "review");
        assertThat(reviews.stream().map(e -> e.a().get("verdict"))).containsExactly("approved", "corrected", "kept_original", "timeout", "error", "unknown", "skipped");
        assertThat(reviews.get(1).a()).containsEntry("differs", true).containsEntry("in", 100).containsEntry("out", 7).containsEntry("model", "glm");
        assertThat(reviews.get(0).d()).isGreaterThanOrEqualTo(1L);
    }

    // ---- privacy and failure isolation -----------------------------------------------------------------------------------------------

    @Test
    void attributesAndEventsOutsideTheAllowlistAreDroppedNotStored() {
        String message = "عايز بنطلون مقاس 42 ورقمي 01012345678 وعنواني شارع النيل";
        ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> {
            ReplyTrace.instant("note", "free_text", message, "phone", "01012345678", "header", "Bearer sk-secret-123 ok", "lines", 1);
            ReplyTrace.instant("customer_message", "lines", 1);   // an event name nobody approved
            ReplyTrace.tool("search مثال", System.nanoTime(), true, 0);
            ReplyTrace.review("approved", System.nanoTime(), "glm", message, false, null);
        });
        String all = t.snapshot().toString();
        assertThat(all).doesNotContain("بنطلون").doesNotContain("01012345678").doesNotContain("Bearer").doesNotContain("sk-secret").doesNotContain("النيل").doesNotContain("free_text").doesNotContain("customer_message");
        assertThat(first(t, "note").a()).containsOnlyKeys("lines");      // the unknown names are gone, the approved one stays
        assertThat(first(t, "tool").a()).containsEntry("tool", "other");   // a tool name outside the lowercase identifier shape is «other»
        assertThat(first(t, "review").a()).containsEntry("detail", "?");   // free text in an approved name is not stored
    }

    @Test
    void aSinkThatFailsNeverFailsTheReply() {
        ReplyTrace.sink = t -> { throw new IllegalStateException("database down"); };
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.finish(false, null);   // must not throw
        assertThat(t).isNotNull();
    }

    @Test
    void theStoreDropsOrCountsAFailedWriteInsteadOfBlockingOrThrowing() throws Exception {
        ReplyTraceStore store = new ReplyTraceStore(null, true);   // no database at all: every write fails
        store.start();
        try {
            ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> ReplyTrace.instant("note", "k", "v"));   // the store's sink is installed by start()
            assertThat(t).isNotNull();
            long until = System.currentTimeMillis() + 5_000;
            while (store.failures() < 1 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertThat(store.failures()).isGreaterThanOrEqualTo(1L);
        } finally { store.stop(); }
    }

    @Test
    void tracingOffMeansNoTraceAtAll() {
        ReplyTrace.enabled = false;
        assertThat(ReplyTrace.begin("WEB")).isNull();
        ReplyTrace.instant("note", "k", "v");   // does nothing, never throws
        ReplyTrace.finish(false, null);
        assertThat(done).isEmpty();
    }

    @Test
    void nothingOverflowsAHugeReply() {
        ReplyTrace t = reply(UUID.randomUUID(), "WEB", () -> { for (int i = 0; i < 1_000; i++) ReplyTrace.instant("note", "i", i); });
        assertThat(t.snapshot().size()).isLessThanOrEqualTo(ReplyTrace.MAX_EVENTS);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}

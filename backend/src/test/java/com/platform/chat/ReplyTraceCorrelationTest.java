package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** One opaque id per reply, shared by the chat.reply_traces row, the AiTelemetry (Langfuse) spans and the log lines; never shared between replies, and intact on an async worker thread. */
class ReplyTraceCorrelationTest {
    java.util.function.Consumer<ReplyTrace> saved;
    final List<ReplyTrace> done = new CopyOnWriteArrayList<>();

    @BeforeEach
    void on() {
        saved = ReplyTrace.sink; ReplyTrace.sink = done::add; ReplyTrace.enabled = true;
        assertThat(AiTelemetry.configure(true, "http://127.0.0.1:9", "pk", "sk", "salt", false, 100, "test")).isTrue();   // an endpoint nobody listens on: spans are queued and dropped, never waited for
    }

    @AfterEach
    void off() { AiTelemetry.configure(false, "", "", "", "", false, 10, ""); ReplyTrace.sink = saved; }

    @Test
    void theTelemetryTraceOfAReplyIsTheSameIdAsItsTraceRow() {
        ReplyTrace t = ReplyTrace.begin("WEB");
        UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
        ReplyTrace.identify(tenant, conv, false, false);
        AiTelemetry.beginReply(tenant, conv, ReplyTrace.currentHexId());
        assertThat(AiTelemetry.currentTrace()).isEqualTo(t.id.replace("-", "")).matches("[0-9a-f]{32}");
        AiTelemetry.endReply("WEB", "x", System.currentTimeMillis());
        ReplyTrace.finish(false, null);
    }

    @Test
    void aMissingOrMalformedIdFallsBackToARandomOneAsBefore() {
        AiTelemetry.beginReply(UUID.randomUUID(), UUID.randomUUID(), null);
        String a = AiTelemetry.currentTrace();
        AiTelemetry.beginReply(UUID.randomUUID(), UUID.randomUUID(), "not hex <script>");
        String b = AiTelemetry.currentTrace();
        AiTelemetry.endReply("WEB", "x", System.currentTimeMillis());
        assertThat(a).matches("[0-9a-f]{32}");
        assertThat(b).matches("[0-9a-f]{32}").isNotEqualTo(a);
    }

    @Test
    void concurrentRepliesNeverShareAnIdAndEachKeepsItsOwnOnItsThread() throws Exception {
        int n = 16;
        var pool = Executors.newFixedThreadPool(n);
        var go = new CountDownLatch(1);
        Set<String> traceIds = ConcurrentHashMap.newKeySet(), telemetryIds = ConcurrentHashMap.newKeySet();
        var mismatches = new CopyOnWriteArrayList<String>();
        for (int i = 0; i < n; i++) pool.execute(() -> {
            try { go.await(); } catch (InterruptedException e) { return; }
            for (int k = 0; k < 20; k++) {
                ReplyTrace t = ReplyTrace.begin("WEB");
                UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
                ReplyTrace.identify(tenant, conv, false, false);
                AiTelemetry.beginReply(tenant, conv, ReplyTrace.currentHexId());
                Thread.yield();
                if (!t.id.replace("-", "").equals(AiTelemetry.currentTrace()) || !t.id.equals(ReplyTrace.currentId())) mismatches.add(t.id);
                traceIds.add(t.id); telemetryIds.add(AiTelemetry.currentTrace());
                AiTelemetry.endReply("WEB", "x", System.currentTimeMillis());
                ReplyTrace.finish(false, null);
            }
        });
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(mismatches).isEmpty();
        assertThat(traceIds).hasSize(n * 20);
        assertThat(telemetryIds).hasSize(n * 20);
    }

    @Test
    void theIdSurvivesTheBurstHoldersWorkerThreadAndCarriesTheHoldersTimings() throws Exception {
        var timers = Executors.newSingleThreadScheduledExecutor();
        var worker = Executors.newFixedThreadPool(2);
        InboundBurst burst = new InboundBurst(timers, worker, 30, 500);
        var seen = new CopyOnWriteArrayList<String[]>();
        var fired = new CountDownLatch(2);
        java.util.function.BiConsumer<String, List<Object>> fire = (text, metas) -> {
            ReplyTrace t = ReplyTrace.begin("WHATSAPP");
            UUID tenant = UUID.randomUUID(), conv = UUID.randomUUID();
            ReplyTrace.identify(tenant, conv, false, false);
            AiTelemetry.beginReply(tenant, conv, ReplyTrace.currentHexId());
            seen.add(new String[]{t.id, AiTelemetry.currentTrace(), String.valueOf(t.snapshot().stream().anyMatch(e -> e.name().equals("burst_wait")))});
            AiTelemetry.endReply("WHATSAPP", "x", System.currentTimeMillis());
            ReplyTrace.finish(false, null);
            fired.countDown();
        };
        burst.add("customer-a", "hi", null, fire);
        burst.add("customer-b", "hello", null, fire);   // two customers, two workers at once
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        timers.shutdown(); worker.shutdown();
        assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)[0]).isNotEqualTo(seen.get(1)[0]);
        for (String[] s : seen) { assertThat(s[1]).isEqualTo(s[0].replace("-", "")); assertThat(s[2]).isEqualTo("true"); }
        assertThat(done).hasSize(2);
        assertThat(done.stream().map(x -> x.id).distinct()).hasSize(2);
    }

    @Test
    void theExistingTimelineLogLineNowCarriesTheSameIdAsTheTraceLine() {
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        Logger timeline = (Logger) LoggerFactory.getLogger(ReplyTimeline.class), trace = (Logger) LoggerFactory.getLogger(ReplyTrace.class);
        Level before = timeline.getLevel();
        timeline.setLevel(Level.INFO); trace.setLevel(Level.INFO);
        timeline.addAppender(lines); trace.addAppender(lines);
        try {
            ReplyTimeline.begin("WEB");
            ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
            String id = ReplyTrace.currentId();
            ReplyTimeline.mark("prep");
            ReplyTimeline.finish("WEB", "ext-1");
            List<String> messages = lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().filter(m -> m.startsWith("Reply timeline:")).findFirst().orElseThrow()).contains(" trace=" + id).contains("channel=WEB conversation=ext-1");   // the existing fields keep their place
            assertThat(messages.stream().filter(m -> m.startsWith("Reply trace:")).findFirst().orElseThrow()).contains("id=" + id);
        } finally { timeline.detachAppender(lines); trace.detachAppender(lines); timeline.setLevel(before); }
    }
}

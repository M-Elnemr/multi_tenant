package com.platform.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes finished {@link ReplyTrace}s to {@code chat.reply_traces}, one row per reply, from a daemon thread. The reply only does a non-blocking {@code offer} on a bounded queue: when the
 * queue is full or the database fails, the trace is dropped (and counted), never waited for. Off with {@code AI_TRACE=false}.
 */
@Component
public class ReplyTraceStore {
    private static final Logger log = LoggerFactory.getLogger(ReplyTraceStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;
    private final boolean on;
    static final int QUEUE_CAPACITY = 2000;
    private final BlockingQueue<ReplyTrace> queue;
    private final AtomicLong dropped = new AtomicLong(), failed = new AtomicLong(), written = new AtomicLong();
    private volatile boolean running = true;
    private Thread writer;
    private volatile long lastWarn;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplyTraceStore(JdbcClient jdbc, @Value("${app.ai.trace.enabled:true}") boolean on) { this(jdbc, on, QUEUE_CAPACITY); }

    ReplyTraceStore(JdbcClient jdbc, boolean on, int capacity) { this.jdbc = jdbc; this.on = on; this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity)); }

    private final java.util.function.Consumer<ReplyTrace> mySink = this::offer;
    private boolean turnedOff;

    @PostConstruct
    void start() {
        if (!on) {   // AI_TRACE=false: no trace is created, nothing is queued or written, no elmanassa.* metric is recorded, no writer thread starts
            ReplyTrace.enabled = false;   // the sink is left alone: another live context (tests keep several) may own it
            turnedOff = true;
            return;
        }
        ReplyTrace.enabled = true;
        ReplyTrace.sink = mySink;
        ReplyMetrics.gauge("elmanassa.trace.queue", queue, BlockingQueue::size);
        ReplyMetrics.gauge("elmanassa.trace.dropped", dropped, AtomicLong::get);
        ReplyMetrics.gauge("elmanassa.trace.write_failures", failed, AtomicLong::get);
        writer = new Thread(this::drain, "reply-trace-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (ReplyTrace.sink == mySink) ReplyTrace.sink = null;   // another live context (tests keep several) may have installed its own
        if (turnedOff) ReplyTrace.enabled = true;               // the default again once the context that switched it off is gone
        if (writer != null) writer.interrupt();
    }

    /** Never blocks: a full queue drops the trace and counts it. */
    void offer(ReplyTrace t) { if (!queue.offer(t)) dropped.incrementAndGet(); }

    private void drain() {
        while (running) {
            try {
                ReplyTrace t = queue.poll(1, TimeUnit.SECONDS);
                if (t == null) continue;
                write(t);
            } catch (InterruptedException e) { return; }
            catch (RuntimeException e) { failed.incrementAndGet(); warn(e); }
        }
    }

    /** Package-private so a test can write one trace synchronously; it throws on a database error, the writer thread catches. */
    void write(ReplyTrace t) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (var e : t.snapshot()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", e.name()); m.put("t", e.t());
            if (e.d() != null) m.put("d", e.d());
            if (!e.a().isEmpty()) m.put("a", e.a());
            events.add(m);
        }
        try {
            Map<String, Object> s = t.summary();
            jdbc.sql("INSERT INTO chat.reply_traces (trace_id, tenant_id, conversation_id, channel, source, started_at, reply_ms, end_to_end_ms, outcome, model, model_calls, summary, events) "
                            + "VALUES (:id, :t, :c, :ch, :src, :at, :rm, :e2e, :o, :m, :mc, CAST(:s AS jsonb), CAST(:ev AS jsonb)) ON CONFLICT (trace_id) DO NOTHING")
                    .param("id", java.util.UUID.fromString(t.id)).param("t", t.tenantId()).param("c", t.conversationId())
                    .param("ch", t.channel.length() > 12 ? t.channel.substring(0, 12) : t.channel).param("src", t.source()).param("at", new Timestamp(t.originWallMs()))
                    .param("rm", (int) Math.min(Integer.MAX_VALUE, t.replyMs())).param("e2e", ((Number) s.getOrDefault("end_to_end_ms", 0L)).intValue())
                    .param("o", t.failed() ? "failed" : "completed").param("m", t.model()).param("mc", (short) t.countCalls())
                    .param("s", JSON.writeValueAsString(s)).param("ev", JSON.writeValueAsString(events)).update();
            written.incrementAndGet();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) { lastWarn = now; log.warn("Reply trace not saved (further failures are counted only): {}", e.toString()); }
    }

    long written() { return written.get(); }

    long failures() { return failed.get(); }

    long dropped() { return dropped.get(); }

    static final int KEEP_DAYS = 60, BATCH = 5_000, MAX_BATCHES = 40;

    /** Traces are kept for 60 days. */
    @Scheduled(cron = "0 50 4 * * *", zone = "Africa/Cairo")
    public void purge() { purge(KEEP_DAYS, BATCH, MAX_BATCHES); }

    /**
     * Deletes traces older than {@code days} in batches (by the started_at index, oldest first), at most {@code maxBatches} of {@code batch} rows per run with a short pause between batches, so a large
     * table is trimmed over a few nights instead of in one long statement, and a failure only ends the run. It runs on the scheduler thread, never on a reply thread. Returns the rows deleted.
     */
    public long purge(int days, int batch, int maxBatches) {
        long total = 0;
        try {
            for (int i = 0; i < maxBatches; i++) {
                int n = jdbc.sql("DELETE FROM chat.reply_traces WHERE trace_id IN (SELECT trace_id FROM chat.reply_traces WHERE started_at < now() - make_interval(days => :d) ORDER BY started_at LIMIT :n)")
                        .param("d", days).param("n", batch).update();
                total += n;
                if (n < batch) break;
                Thread.sleep(100);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { warn(e); }
        return total;
    }
}

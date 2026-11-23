package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The website chat answers like a person: the customer's lines are saved the moment they arrive, the assistant waits for a quiet window (the same {@link InboundBurst} as the messaging
 * channels), reads every unanswered line together and replies once; the page polls the history for it. {@code CHAT_WEB_BURST=false} brings back the old one-request-one-reply behaviour.
 */
@Component
public class WebBurst {
    private static final Logger log = LoggerFactory.getLogger(WebBurst.class);

    /** What travels with one website line: its stored message id (set once a photo or voice note is read and saved) and the message it answers. */
    static final class Meta {
        volatile long id;
        final ReplyRef ref;
        Meta(long id, ReplyRef ref) { this.id = id; this.ref = ref; }
    }

    private final ChatService chat;
    private final ConversationGate gate;
    private final boolean on;
    private final ExecutorService pool;
    private final ScheduledExecutorService timers;
    private final InboundBurst burst;
    private final Map<Long, Map<String, Object>> extras = new LinkedHashMap<>() {   // the newest 2000 replies' cards; the page shows them once, as the old synchronous answer did
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Map<String, Object>> e) { return size() > 2000; }
    };
    private final ConcurrentHashMap<String, Long> lastTyping = new ConcurrentHashMap<>();

    public WebBurst(@org.springframework.context.annotation.Lazy ChatService chat, ConversationGate gate, @Value("${app.chat.web-burst:true}") boolean webBurst,
                    @Value("${app.chat.web-burst-ms:0}") long burstMs, @Value("${app.chat.burst-max-ms:15000}") long burstMaxMs, @Value("${app.chat.web-workers:4}") int workers) {
        this.chat = chat;
        this.gate = gate;
        this.on = webBurst;   // the website starts answering at once (web-burst-ms = 0): a newer line drops the answer in progress and one answer covers every line (ConversationGate)
        this.pool = on ? Executors.newFixedThreadPool(Math.max(1, workers), r -> { Thread t = new Thread(r, "web-chat"); t.setDaemon(true); return t; }) : null;
        this.timers = on ? Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "web-chat-timer"); t.setDaemon(true); return t; }) : null;
        this.burst = on ? new InboundBurst(timers, pool, burstMs, burstMaxMs) : new InboundBurst(null, Runnable::run, 0, 0);
        if (on) ReplyMetrics.pool("web", pool);
    }

    public boolean enabled() { return on; }

    static String key(UUID tenantId, String conversationId) { return tenantId + "|WEB|" + conversationId; }

    /** A line of the conversation was saved as message {@code id}: it joins the burst. */
    public void add(UUID tenantId, String conversationId, UUID clientId, long id, ReplyRef ref, String text) {
        String key = key(tenantId, conversationId);
        burst.add(key, text, new Meta(id, ref), handler(key, tenantId, conversationId, clientId));
    }

    /** A photo or voice note is being read: the burst waits for it. Call the result once with the text read (null when nothing could be read), after saving it (see {@link #fill}). */
    public Consumer<String> hold(UUID tenantId, String conversationId, UUID clientId, Meta meta) {
        String key = key(tenantId, conversationId);
        return burst.hold(key, meta, handler(key, tenantId, conversationId, clientId));
    }

    private java.util.function.BiConsumer<String, List<Object>> handler(String key, UUID tenantId, String conversationId, UUID clientId) {
        return (joined, metas) -> gate.run(key, joined, metas, (j, m, carried) -> answer(tenantId, conversationId, clientId, j, m, carried));
    }

    private void answer(UUID tenantId, String conversationId, UUID clientId, String joined, List<Object> metas, String carried) {
        if (joined == null || joined.isBlank()) return;   // nothing readable in the whole burst
        List<ReplyRef> refs = new ArrayList<>();
        long last = 0;
        for (Object o : metas) {
            InboundBurst.Line line = (InboundBurst.Line) o;
            if (!(line.meta() instanceof Meta m)) continue;
            if (m.ref != null && !m.ref.empty()) refs.add(m.ref.withLine(line.text()));
            last = Math.max(last, m.id);
        }
        var in = new ChatService.ReplyInput(refs, List.of()).withCarried(carried);
        if (last > 0) in = in.withStored(last);
        try {
            var reply = chat.reply(tenantId, "WEB", conversationId, clientId, joined.length() > 1000 ? joined.substring(0, 1000) : joined, null, in);
            if (reply.messageId() > 0 && (reply.order() != null || !reply.products().isEmpty())) {   // the order card and product cards of this reply, shown with it when the page loads the history
                Map<String, Object> x = new LinkedHashMap<>();
                if (reply.order() != null) x.put("order", reply.order());
                if (!reply.products().isEmpty()) x.put("products", reply.products());
                synchronized (extras) { extras.put(reply.messageId(), x); }
            }
        } catch (RuntimeException e) {
            log.warn("Website chat reply failed for {}: {}", conversationId, e.toString());
        }
    }

    /** Is the assistant still to answer this chat (a burst waiting for its window, or a reply being written)? The page shows the typing dots meanwhile. */
    public boolean pending(UUID tenantId, String conversationId) {
        if (!on) return false;
        String key = key(tenantId, conversationId);
        return burst.waiting(key) || gate.busy(key);
    }

    /** The customer is typing: the quiet window of the lines already waiting restarts. At most four times a second per chat; costs nothing when no line is waiting. */
    public void typing(UUID tenantId, String conversationId) {
        if (!on) return;
        String key = key(tenantId, conversationId);
        long now = System.currentTimeMillis();
        Long before = lastTyping.put(key, now);
        if (lastTyping.size() > 5000) lastTyping.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        if (before != null && now - before < 250) return;
        burst.touch(key);
    }

    /** The order and product cards that went with assistant message {@code id} (null when none). */
    public Map<String, Object> extras(long id) { synchronized (extras) { return extras.get(id); } }

    /** Test hook: waits until nothing is pending for this chat. */
    boolean idle(UUID tenantId, String conversationId) { return !pending(tenantId, conversationId); }
}

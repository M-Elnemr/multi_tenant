package com.platform.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One reply generation per conversation, on every channel. A person never answers a customer twice at once: if more lines arrive (a new burst) while a reply for the same conversation is
 * still being written and has NOT been sent or saved, that reply is thrown away and ONE new reply is written over all the unanswered lines; if the reply was already sent, the new burst is
 * answered after it. The waiting burst never holds a worker thread: it is parked here and the generation that is finishing runs it on its own thread.
 * Off ({@code CHAT_GATE=false}): every burst runs at once, as before.
 */
@Component
public class ConversationGate {
    private static final Logger log = LoggerFactory.getLogger(ConversationGate.class);

    /** Thrown inside a generation that was superseded, to stop it spending more model calls; ChatService turns it into «send nothing». */
    static final class Superseded extends RuntimeException {
        Superseded() { super("superseded by a newer burst", null, false, false); }
    }

    /** One running generation. {@code cancel}: a newer burst is waiting. {@code discarded}: the generation threw its reply away (nothing was saved or sent). */
    static final class Gen {
        volatile boolean cancel;
        volatile boolean discarded;
    }

    /** What a burst's handler gets: the lines joined, the metas, and the text of earlier lines whose reply was discarded (already stored; they are context, never stored again). */
    @FunctionalInterface
    interface Fire { void run(String joined, List<Object> metas, String carried); }

    private static final ThreadLocal<Gen> CURRENT = new ThreadLocal<>();

    /** Is the generation running on this thread superseded by a newer burst (so its work can stop)? */
    static boolean shouldAbort() { Gen g = CURRENT.get(); return g != null && g.cancel; }

    /** The generation running on this thread dropped its reply: its lines must be answered again by the next one. */
    static void markDiscarded() { Gen g = CURRENT.get(); if (g != null) g.discarded = true; }

    private static final class Queued {
        final StringBuilder text; final List<Object> metas; Fire fire;
        Queued(String text, List<Object> metas, Fire fire) { this.text = new StringBuilder(text); this.metas = new ArrayList<>(metas); this.fire = fire; }
    }

    private static final class State {
        Gen running;
        Queued next;
    }

    private final Map<String, State> states = new HashMap<>();
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationGate(@Value("${app.chat.gate-enabled:true}") boolean enabled) { this.enabled = enabled; }

    /** For tests and plain construction. */
    ConversationGate() { this(true); }

    public boolean enabled() { return enabled; }

    /** Is a reply for this conversation being written, or waiting for the one being written? (the website's «typing» state) */
    public boolean busy(String key) { synchronized (this) { State s = states.get(key); return s != null && (s.running != null || s.next != null); } }

    /**
     * Runs {@code fire} for the burst of conversation {@code key} on the calling thread, unless a generation of the same conversation is running: then the burst is parked (merged with any burst
     * already parked), the running one is told to stop if it can, and this call returns at once (the finishing generation runs the parked burst).
     */
    void run(String key, String joined, List<Object> metas, Fire fire) {
        if (!enabled) { fire.run(joined, metas, null); return; }
        Gen gen;
        synchronized (this) {
            State st = states.computeIfAbsent(key, k -> new State());
            if (st.running != null) {
                st.running.cancel = true;
                if (st.next == null) st.next = new Queued(joined, metas, fire);
                else {
                    if (!joined.isEmpty()) { if (st.next.text.length() > 0) st.next.text.append('\n'); st.next.text.append(joined); }
                    st.next.metas.addAll(metas);
                    st.next.fire = fire;
                }
                return;
            }
            gen = st.running = new Gen();
        }
        String carried = null;
        while (true) {
            CURRENT.set(gen);
            try { fire.run(joined, metas, carried); }
            catch (RuntimeException | Error e) { log.warn("Reply generation for {} failed: {}", key, e.toString()); }
            finally { CURRENT.remove(); }
            synchronized (this) {
                State st = states.get(key);
                Queued q = st == null ? null : st.next;
                if (q == null) { states.remove(key); return; }
                st.next = null;
                // the reply was dropped: its lines were never answered, so the next generation reads them too; a reply that went out is simply followed by the next one
                carried = gen.discarded ? join(carried, joined) : null;
                gen = st.running = new Gen();
                joined = q.text.toString(); metas = q.metas; fire = q.fire;
            }
        }
    }

    private final Map<String, Long> fillers = new java.util.concurrent.ConcurrentHashMap<>();

    /** The «one moment…» line may go out once per conversation turn (not again within a minute, e.g. when a newer burst restarts the reply). */
    boolean fillerAllowed(String key) {
        long now = System.currentTimeMillis();
        if (fillers.size() > 5000) fillers.entrySet().removeIf(e -> now - e.getValue() > 120_000);
        Long before = fillers.put(key, now);
        if (before != null && now - before < 60_000) { fillers.put(key, before); return false; }
        return true;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + "\n" + b;
    }
}

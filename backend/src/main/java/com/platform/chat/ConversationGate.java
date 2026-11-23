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
 * One reply goes out per conversation at a time, on every channel. A person never answers a customer twice at once: if more lines arrive (a new burst) while a reply for the same conversation is
 * still being written and has NOT reached the point of being saved or sent, that reply is dropped and ONE new reply is written over all the unanswered lines, and the new one STARTS AT ONCE (it does
 * not wait for the slow model call of the old one: the old one stops at its next check and its answer is thrown away). If the reply is already going out (or changed something that cannot be undone,
 * like an order), the new burst is answered after it. Off ({@code CHAT_GATE=false}): every burst runs at once, as before.
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
        boolean committed;   // guarded by this: the reply is going out (or already changed state), so a newer burst can no longer drop it
        volatile String lines = "";   // every line this generation answers (its own and the ones carried over from a dropped one)
    }

    /** What a burst's handler gets: the lines joined, the metas, and the text of earlier lines whose reply was discarded (already stored; they are context, never stored again). */
    @FunctionalInterface
    interface Fire { void run(String joined, List<Object> metas, String carried); }

    private static final ThreadLocal<Gen> CURRENT = new ThreadLocal<>();

    /** Is the generation running on this thread superseded by a newer burst (so its work can stop)? */
    static boolean shouldAbort() { Gen g = CURRENT.get(); return g != null && g.cancel; }

    /**
     * The generation on this thread is about to save and send its reply. True: it may (a newer burst arriving from now on waits for it). False: a newer burst already took over, drop the reply.
     * Outside a generation (the gate off, a test) it is always true.
     */
    static boolean tryCommit() {
        Gen g = CURRENT.get();
        if (g == null) return true;
        synchronized (g) { if (g.cancel) return false; g.committed = true; return true; }
    }

    /** The generation on this thread already did something that cannot be undone (an order, a cancellation): its reply goes out whatever arrives, a newer burst waits for it. */
    static void markCommitted() {
        Gen g = CURRENT.get();
        if (g != null) synchronized (g) { if (!g.cancel) g.committed = true; }
    }

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
        String carried = null;
        synchronized (this) {
            State st = states.computeIfAbsent(key, k -> new State());
            Gen old = st.running;
            if (old != null) {
                boolean supersede;
                synchronized (old) { supersede = !old.committed; if (supersede) old.cancel = true; }
                if (!supersede) {   // the running reply is already going out (or cannot be undone): this burst is answered right after it
                    if (st.next == null) st.next = new Queued(joined, metas, fire);
                    else {
                        if (!joined.isEmpty()) { if (st.next.text.length() > 0) st.next.text.append('\n'); st.next.text.append(joined); }
                        st.next.metas.addAll(metas);
                        st.next.fire = fire;
                    }
                    return;
                }
                carried = old.lines;   // the old reply will be dropped: the lines it answered are context for this one, which starts now on this thread
                if (st.next != null) {   // a burst that was waiting for the old one is part of this one
                    joined = join(st.next.text.toString(), joined);
                    List<Object> all = new ArrayList<>(st.next.metas); all.addAll(metas); metas = all;
                    st.next = null;
                }
            }
            gen = st.running = new Gen();
            gen.lines = join(carried, joined);
        }
        while (true) {
            CURRENT.set(gen);
            try { fire.run(joined, metas, carried); }
            catch (RuntimeException | Error e) { log.warn("Reply generation for {} failed: {}", key, e.toString()); }
            finally { CURRENT.remove(); }
            synchronized (this) {
                State st = states.get(key);
                if (st == null || st.running != gen) return;   // a newer generation took the conversation over: it carries this one's lines
                Queued q = st.next;
                if (q == null) { states.remove(key); return; }
                st.next = null;
                // the reply was dropped: its lines were never answered, so the next generation reads them too; a reply that went out is simply followed by the next one
                carried = gen.discarded ? gen.lines : null;
                gen = st.running = new Gen();
                joined = q.text.toString(); metas = q.metas; fire = q.fire;
                gen.lines = join(carried, joined);
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

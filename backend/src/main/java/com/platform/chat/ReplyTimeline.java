package com.platform.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the time of one customer reply goes: the phases of {@code ChatService.reply} (preparation, system prompt, the model flow, saving) and, inside them, every model call, tool and check
 * with its own duration. It lives on the thread of the reply, costs a few nanoTime calls, does nothing outside a reply and can never fail one (every method swallows its own errors).
 * One line per reply goes to the log ({@code Reply timeline: ...}); an evaluation run keeps it with each turn.
 */
final class ReplyTimeline {
    private static final ThreadLocal<ReplyTimeline> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST = new ThreadLocal<>();

    private final long start = System.nanoTime();
    private long lastMark = start;
    private int llmCalls = 0;
    private final List<String> phases = new ArrayList<>(), nested = new ArrayList<>();

    private ReplyTimeline() {}

    static void begin() { CURRENT.set(new ReplyTimeline()); LAST.remove(); }

    /** Ends a phase: the time since the previous mark, with the model calls, tools and checks that happened inside it. */
    static void mark(String phase) {
        try {
            ReplyTimeline t = CURRENT.get();
            if (t == null) return;
            long now = System.nanoTime();
            t.phases.add(phase + "=" + (now - t.lastMark) / 1_000_000 + (t.nested.isEmpty() ? "" : "(" + String.join(" ", t.nested) + ")"));
            t.nested.clear();
            t.lastMark = now;
        } catch (RuntimeException ignored) { /* a timeline never fails a reply */ }
    }

    /** One model call that took {@code ms}: «llm3:deepseek=4100». */
    static void llm(String model, long ms) {
        try { ReplyTimeline t = CURRENT.get(); if (t != null) t.nested.add("llm" + (++t.llmCalls) + ":" + model + "=" + ms); } catch (RuntimeException ignored) { /* see mark */ }
    }

    /** A tool, a review or a check that took {@code ms}: «tool:search_products=310». */
    static void span(String label, long ms) {
        try { ReplyTimeline t = CURRENT.get(); if (t != null) t.nested.add(label + "=" + ms); } catch (RuntimeException ignored) { /* see mark */ }
    }

    static long since(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    /** Ends the reply's timeline: the rest is «post», the summary is logged and kept for {@link #takeLast()}. */
    static void finish(String channel, Object conversation) {
        try {
            ReplyTimeline t = CURRENT.get();
            if (t == null) return;
            mark("post");
            String summary = "total=" + (System.nanoTime() - t.start) / 1_000_000 + " " + String.join(" ", t.phases);
            LAST.set(summary);
            org.slf4j.LoggerFactory.getLogger(ReplyTimeline.class).info("Reply timeline: {} channel={} conversation={}", summary, channel, conversation);
        } catch (RuntimeException ignored) { /* see mark */ }
        finally { CURRENT.remove(); }
    }

    /** The summary of the reply that just finished on this thread (an evaluation reads it after {@code chat.reply} returns), once. */
    static String takeLast() { String s = LAST.get(); LAST.remove(); return s; }
}

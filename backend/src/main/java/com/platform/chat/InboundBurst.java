package com.platform.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * People type in bursts: "hi", "do you have the black dress", "size M". Answering each line costs a model call (with the whole prompt) and sends three replies that cross each other.
 * This holds a customer's messages for a short quiet window, joins them, and answers once. The window restarts with every new message but never runs past {@code maxMs} in total.
 */
final class InboundBurst {
    private static final int MAX_CHARS = 1000;   // what one model request takes from a customer message

    private static final class Entry {
        final StringBuilder text = new StringBuilder();
        final long first = System.currentTimeMillis();
        final Consumer<String> fire;
        ScheduledFuture<?> timer;
        Entry(Consumer<String> fire) { this.fire = fire; }
    }

    private final ScheduledExecutorService timers;
    private final Executor worker;
    private final long windowMs, maxMs;
    private final Map<String, Entry> pending = new HashMap<>();

    InboundBurst(ScheduledExecutorService timers, Executor worker, long windowMs, long maxMs) {
        this.timers = timers; this.worker = worker; this.windowMs = windowMs; this.maxMs = Math.max(windowMs, maxMs);
    }

    boolean enabled() { return windowMs > 0; }

    /** Adds one message of the conversation {@code key}; {@code fire} (the one passed with the burst's first message) runs once with all the text. */
    synchronized void add(String key, String text, Consumer<String> fire) {
        Entry e = pending.get(key);
        if (e == null) { e = new Entry(fire); pending.put(key, e); }
        else e.timer.cancel(false);
        if (e.text.length() < MAX_CHARS) { if (e.text.length() > 0) e.text.append('\n'); e.text.append(text, 0, Math.min(text.length(), MAX_CHARS - e.text.length())); }
        long wait = Math.max(0, Math.min(windowMs, maxMs - (System.currentTimeMillis() - e.first)));
        Entry entry = e;
        e.timer = timers.schedule(() -> flush(key, entry), wait, TimeUnit.MILLISECONDS);
    }

    private void flush(String key, Entry entry) {
        String all;
        synchronized (this) {
            if (pending.get(key) != entry) return;   // already flushed by a later timer
            pending.remove(key);
            all = entry.text.toString();
        }
        worker.execute(() -> entry.fire.accept(all));
    }
}

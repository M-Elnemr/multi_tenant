package com.platform.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        final List<String> parts = new ArrayList<>();   // in the order the customer sent them; null = a photo or voice note still being read
        final long first = System.currentTimeMillis();
        final Consumer<String> fire;
        int waiting;   // photos and voice notes still being read: the burst is not answered before they are
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
        Entry e = entry(key, fire);
        e.parts.add(text);
        restart(key, e);
    }

    /**
     * Keeps a place in the burst for a photo or voice note that is still being read (a few seconds), so «بكام ده؟» sent right after the photo is answered together with it and in
     * order. Call the returned function once with the text read (null when nothing could be read); the burst is not answered before that.
     */
    synchronized Consumer<String> hold(String key, Consumer<String> fire) {
        Entry e = entry(key, fire);
        int slot = e.parts.size();
        e.parts.add(null);
        e.waiting++;
        if (e.timer != null) e.timer.cancel(false);
        boolean[] done = {false};
        return text -> {
            synchronized (this) {
                if (done[0]) return;
                done[0] = true;
                e.parts.set(slot, text);
                e.waiting--;
                if (pending.get(key) == e) restart(key, e);
            }
        };
    }

    private Entry entry(String key, Consumer<String> fire) {
        Entry e = pending.get(key);
        if (e == null) { e = new Entry(fire); pending.put(key, e); }
        else if (e.timer != null) e.timer.cancel(false);
        return e;
    }

    private void restart(String key, Entry e) {
        if (e.waiting > 0) return;   // the photo's text restarts the window when it is read
        long wait = Math.max(0, Math.min(windowMs, maxMs - (System.currentTimeMillis() - e.first)));
        e.timer = timers.schedule(() -> flush(key, e), wait, TimeUnit.MILLISECONDS);
    }

    private void flush(String key, Entry entry) {
        StringBuilder all = new StringBuilder();
        synchronized (this) {
            if (pending.get(key) != entry || entry.waiting > 0) return;   // already flushed by a later timer, or a photo is still being read
            pending.remove(key);
            for (String part : entry.parts) {
                if (part == null || part.isBlank() || all.length() >= MAX_CHARS) continue;
                if (all.length() > 0) all.append('\n');
                all.append(part, 0, Math.min(part.length(), MAX_CHARS - all.length()));
            }
        }
        worker.execute(() -> entry.fire.accept(all.toString()));
    }
}

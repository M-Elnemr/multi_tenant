package com.platform.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Remembers, per model code, whether a provider is answering, so a provider that hangs is not asked again by every new customer message while each of them waits the full timeout (DR-R).
 * A sliding window of the last {@value #WINDOW_MS} ms of real calls per model: the breaker OPENS after {@value #FAILURES_TO_OPEN} failures (timeout, connection, 5xx, 429) in the window, or when the median call
 * time of the window is over {@value #SLOW_P50_MS} ms (at least {@value #MIN_SLOW_SAMPLES} calls). While open, {@link #allow} says no, and new replies go straight to the fallback model; one request per
 * {@value #PROBE_EVERY_MS} ms is let through to the provider (half-open): when it answers in time the breaker closes, when it fails it stays open. Thread-safe; one lock per instance, never held across a call.
 */
final class ProviderHealth {
    static final long WINDOW_MS = 60_000, PROBE_EVERY_MS = 30_000, SLOW_P50_MS = 20_000;
    static final int FAILURES_TO_OPEN = 2, MIN_SLOW_SAMPLES = 3;

    private record Sample(long at, boolean ok, long ms) {}

    private static final class State {
        final ArrayDeque<Sample> window = new ArrayDeque<>();
        boolean open;
        long lastProbe;
    }

    private final Map<String, State> states = new HashMap<>();
    private final LongSupplier clock;
    private volatile boolean enabled = true;

    ProviderHealth() { this(System::currentTimeMillis); }

    ProviderHealth(LongSupplier clock) { this.clock = clock; }

    void enabled(boolean on) { enabled = on; }

    boolean enabled() { return enabled; }

    /** The failure categories that mean the provider itself is not serving: an empty or malformed answer is not one of them. */
    static boolean counts(String category) {
        return ProviderFailure.TIMEOUT.equals(category) || ProviderFailure.CONNECTION.equals(category) || ProviderFailure.SERVER_ERROR.equals(category) || ProviderFailure.RATE_LIMIT.equals(category);
    }

    /**
     * May this request go to {@code code}? Always true while closed (and when the breaker is switched off). While open, true for one request per {@value #PROBE_EVERY_MS} ms (the caller of that
     * request must report its outcome with {@link #success} or {@link #failure}).
     */
    boolean allow(String code) {
        if (!enabled) return true;
        synchronized (this) {
            State s = states.get(code);
            if (s == null || !s.open) return true;
            long now = clock.getAsLong();
            if (now - s.lastProbe >= PROBE_EVERY_MS) { s.lastProbe = now; return true; }
            return false;
        }
    }

    /** True while the breaker of {@code code} is open (a probe may still be let through by {@link #allow}). */
    boolean isOpen(String code) {
        synchronized (this) { State s = states.get(code); return s != null && s.open; }
    }

    /** A call that returned an answer in {@code ms}. Closes an open breaker unless the answer itself was slow. */
    void success(String code, long ms) {
        if (!enabled) return;
        synchronized (this) {
            State s = state(code);
            long now = clock.getAsLong();
            s.window.addLast(new Sample(now, true, ms));
            trim(s, now);
            if (s.open) {
                if (ms <= SLOW_P50_MS) { s.open = false; s.window.clear(); }   // a probe that answered in time: healthy again, start the window afresh
            } else if (slow(s)) { s.open = true; s.lastProbe = now; }
        }
    }

    /** A call that failed after {@code ms}; only the categories of {@link #counts} are held against the provider. */
    void failure(String code, String category, long ms) {
        if (!enabled || !counts(category)) return;
        synchronized (this) {
            State s = state(code);
            long now = clock.getAsLong();
            s.window.addLast(new Sample(now, false, ms));
            trim(s, now);
            if (s.open) { s.lastProbe = now; return; }   // the probe failed: stay open, next probe in 30 s
            long failures = s.window.stream().filter(x -> !x.ok()).count();
            if (failures >= FAILURES_TO_OPEN || slow(s)) { s.open = true; s.lastProbe = now; }
        }
    }

    private State state(String code) { return states.computeIfAbsent(code, k -> new State()); }

    private static void trim(State s, long now) { while (!s.window.isEmpty() && now - s.window.peekFirst().at() > WINDOW_MS) s.window.pollFirst(); }

    /** The median call time of the window is over the limit (failures count with the time they took). */
    private static boolean slow(State s) {
        if (s.window.size() < MIN_SLOW_SAMPLES) return false;
        List<Long> ms = new ArrayList<>();
        for (Sample x : s.window) ms.add(x.ms());
        Collections.sort(ms);
        return ms.get(ms.size() / 2) > SLOW_P50_MS;
    }
}

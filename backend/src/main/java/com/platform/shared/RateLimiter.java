package com.platform.shared;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window request counter per key (spec 44). In-memory for a single node; the same interface can sit on Redis later. */
public class RateLimiter {
    private record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** True if the call is allowed. */
    public boolean tryAcquire(String key, int max, Duration window) {
        long now = System.currentTimeMillis();
        long len = window.toMillis();
        if (windows.size() > 200_000) windows.entrySet().removeIf(e -> now - e.getValue().start() > len);
        boolean[] ok = {true};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.start() >= len) return new Window(now, 1);
            if (w.count() >= max) { ok[0] = false; return w; }
            return new Window(w.start(), w.count() + 1);
        });
        return ok[0];
    }
}

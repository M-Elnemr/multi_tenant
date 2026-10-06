package com.platform.core.auth;

import com.platform.shared.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Sliding-window brute-force guard (spec 44). In-memory for one VPS; swap for Redis when scaling out. */
@Component
public class LoginThrottle {
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public void check(String key, int max, Duration window) {
        Instant now = Instant.now();
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(window))) q.pollFirst();
            if (q.size() >= max) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many attempts, try again later");
            q.addLast(now);
        }
    }

    public void reset(String key) { hits.remove(key); }

    /** Test hook. */
    public void clearAll() { hits.clear(); }
}

package com.platform.shared;

import java.time.Duration;

/** Fixed-window counters shared by rate limiting and login throttling. In-memory for one node; Redis when several backend instances run. */
public interface RateLimitStore {
    /** Counts one hit for the key; true while the number of hits in the current window is within max. */
    boolean tryAcquire(String key, int max, Duration window);

    void reset(String key);

    /** Test hook. */
    void clearAll();
}

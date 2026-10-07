package com.platform.core.auth;

import com.platform.shared.BusinessException;
import com.platform.shared.RateLimitStore;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Brute-force guard (spec 44), backed by the shared rate-limit store so it holds across backend instances. */
@Component
public class LoginThrottle {
    private final RateLimitStore store;

    public LoginThrottle(RateLimitStore store) { this.store = store; }

    public void check(String key, int max, Duration window) {
        if (!store.tryAcquire("throttle:" + key, max, window))
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many attempts, try again later");
    }

    public void reset(String key) { store.reset("throttle:" + key); }

    /** Test hook. */
    public void clearAll() { store.clearAll(); }
}

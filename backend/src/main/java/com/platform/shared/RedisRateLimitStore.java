package com.platform.shared;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Same limits for every backend instance. A failing Redis must not take the platform down, so on a Redis error we allow the request
 * (fail open) and log it; login throttling then degrades to "no throttle" only while Redis is unreachable.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisRateLimitStore implements RateLimitStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisRateLimitStore.class);
    private static final String PREFIX = "rl:";

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public boolean tryAcquire(String key, int max, Duration window) {
        try {
            String k = PREFIX + key;
            Long n = redis.opsForValue().increment(k);
            if (n == null) return true;
            // the TTL is (re)applied whenever it is missing, so a crash between INCR and EXPIRE can never leave a key that never expires
            if (n == 1 || Boolean.TRUE.equals(redis.getExpire(k) != null && redis.getExpire(k) < 0)) redis.expire(k, window);
            return n <= max;
        } catch (RuntimeException e) {
            log.warn("Redis rate limit unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void reset(String key) {
        try { redis.delete(PREFIX + key); } catch (RuntimeException e) { log.warn("Redis reset failed: {}", e.getMessage()); }
    }

    @Override
    public void clearAll() {
        try {
            var keys = redis.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (RuntimeException e) { log.warn("Redis clear failed: {}", e.getMessage()); }
    }
}

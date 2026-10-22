package com.platform.integrations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The real network. Calls that share a {@code rateKey} are spaced at least {@code minGapMs} apart; 429 and 5xx answers are retried a few times,
 * waiting as long as the platform's Retry-After says (capped), otherwise a growing delay. Never logs bodies or credentials.
 */
@Component
public class JdkHttpGateway implements HttpGateway {
    private static final Logger log = LoggerFactory.getLogger(JdkHttpGateway.class);
    private static final int ATTEMPTS = 3;
    private static final long MAX_WAIT_MS = 15_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final long minGapMs;

    public JdkHttpGateway(@Value("${app.integrations.min-gap-ms:260}") long minGapMs) { this.minGapMs = minGapMs; }

    @Override
    public Res send(Req req) {
        Res res = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            pace(req.rateKey());
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.url())).timeout(req.timeout() == null ? Duration.ofSeconds(30) : req.timeout());
                req.headers().forEach(b::header);
                b.header("User-Agent", req.headers().getOrDefault("User-Agent", "Elmanassa-Integrations/1.0"));
                HttpRequest.BodyPublisher body = req.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(req.body());
                b.method(req.method(), body);
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                res = new Res(r.statusCode(), r.body(), r.headers().map());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(0, "The request was interrupted");
            } catch (Exception e) {
                if (attempt == ATTEMPTS) throw new ApiException(0, "Could not reach the platform (" + e.getClass().getSimpleName() + ")");
                sleep(backoff(attempt, null));
                continue;
            }
            boolean retry = res.status() == 429 || res.status() == 502 || res.status() == 503 || res.status() == 504;
            if (!retry || attempt == ATTEMPTS) return res;
            long wait = backoff(attempt, res.header("Retry-After"));
            log.debug("Platform answered {} - retrying in {} ms", res.status(), wait);
            sleep(wait);
        }
        return res;
    }

    /** A rate key may carry its own spacing ("amazon-orders|gap=2200" = at least 2.2 s between calls); otherwise the default gap applies. */
    private void pace(String key) {
        if (key == null || minGapMs <= 0) return;
        long gap = minGapMs;
        int at = key.indexOf("|gap=");
        if (at > 0) { try { gap = Long.parseLong(key.substring(at + 5)); } catch (NumberFormatException ignored) { /* default */ } }
        long wait;
        synchronized (last) {
            long now = System.currentTimeMillis();
            long next = Math.max(now, last.getOrDefault(key, 0L) + gap);
            last.put(key, next);
            wait = next - now;
        }
        if (wait > 0) sleep(wait);
    }

    private static long backoff(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try { return Math.min(MAX_WAIT_MS, Math.max(500, Long.parseLong(retryAfter.trim()) * 1000)); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return Math.min(MAX_WAIT_MS, 800L * (1L << (attempt - 1)));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

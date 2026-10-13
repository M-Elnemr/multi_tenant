package com.platform.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * A staff phone that was offline replays its queued actions with an Idempotency-Key. When a key was already served for this clinic the stored
 * answer is returned and nothing runs twice (no second walk-in, no duplicate patient). Only successful answers are stored.
 */
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> WRITES = Set.of("POST", "PATCH", "PUT", "DELETE");
    private final JdbcClient jdbc;

    public IdempotencyFilter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String key = req.getHeader("Idempotency-Key");
        TenantContext.Current t = TenantContext.get();
        if (key == null || key.isBlank() || key.length() > 100 || t == null || !WRITES.contains(req.getMethod()) || !req.getRequestURI().startsWith("/api/v1/clinic/")) {
            chain.doFilter(req, res);
            return;
        }
        var stored = jdbc.sql("SELECT status, body FROM core.request_replays WHERE tenant_id = :t AND idem_key = :k").param("t", t.id()).param("k", key).query().listOfRows().stream().findFirst();
        if (stored.isPresent()) {
            res.setStatus(((Number) stored.get().get("status")).intValue());
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.setHeader("Idempotent-Replay", "true");
            if (stored.get().get("body") != null) res.getWriter().write((String) stored.get().get("body"));
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                try {
                    jdbc.sql("INSERT INTO core.request_replays (tenant_id, idem_key, status, body) VALUES (:t, :k, :s, :b) ON CONFLICT DO NOTHING")
                            .param("t", t.id()).param("k", key).param("s", status).param("b", body.isEmpty() ? null : body).update();
                } catch (RuntimeException ignored) { /* storing is best effort */ }
            }
            wrapped.copyBodyToResponse();
        }
    }
}

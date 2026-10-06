package com.platform.core.auth;

import com.platform.shared.ClientInfo;
import com.platform.shared.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Spec 44 starting limits, per user (or IP when anonymous). Everything else falls under one generous per-IP ceiling. */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RateLimiter limiter = new RateLimiter();
    private final JwtService jwt;
    private final boolean enabled;

    public RateLimitFilter(JwtService jwt, boolean enabled) {
        this.jwt = jwt;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) { return !enabled || !req.getRequestURI().startsWith("/api/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String ip = ClientInfo.ip(req);
        String who = ip;
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) who = jwt.parse(h.substring(7)).map(u -> "u:" + u).orElse(ip);
        String path = req.getRequestURI();
        String m = req.getMethod();

        boolean ok = limiter.tryAcquire("all|" + ip, 600, MINUTE);
        if (ok && "POST".equals(m)) {
            if (path.equals("/api/v1/shop/checkout")) ok = limiter.tryAcquire("checkout|" + who, 10, MINUTE);
            else if (path.equals("/api/v1/portal/appointments") || path.equals("/api/v1/clinic/appointments")) ok = limiter.tryAcquire("appt|" + who, 10, MINUTE);
            else if (path.equals("/api/v1/files/presign")) ok = limiter.tryAcquire("presign|" + who, 20, MINUTE);
        } else if (ok && "GET".equals(m) && (path.equals("/api/v1/shop/products") || path.equals("/api/v1/clinic/public/slots"))) {
            ok = limiter.tryAcquire("search|" + ip, 60, MINUTE);
        }
        if (!ok) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many requests, slow down\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}

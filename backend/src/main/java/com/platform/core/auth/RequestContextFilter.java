package com.platform.core.auth;

import com.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts requestId / tenantId / userId on every log line (spec 66) and returns the request id to the caller. Never logs tokens, bodies or PII. */
public class RequestContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String incoming = req.getHeader("X-Request-Id");
        String id = incoming != null && incoming.matches("[A-Za-z0-9._-]{8,64}") ? incoming : UUID.randomUUID().toString();
        res.setHeader("X-Request-Id", id);
        MDC.put("requestId", id);
        TenantContext.Current t = TenantContext.get();
        if (t != null) MDC.put("tenantId", t.id().toString());
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof UUID u) MDC.put("userId", u.toString());
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}

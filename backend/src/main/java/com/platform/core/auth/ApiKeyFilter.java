package com.platform.core.auth;

import com.platform.commerce.ApiKeyService;
import com.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** An {@code X-Api-Key} header on a shop's own address gives read-only access to the public API of THAT shop (key of another shop or a revoked key: nothing). */
public class ApiKeyFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;

    public ApiKeyFilter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String key = req.getHeader("X-Api-Key");
        TenantContext.Current t = TenantContext.get();
        if (key != null && !key.isBlank() && t != null && SecurityContextHolder.getContext().getAuthentication() == null && req.getRequestURI().startsWith("/api/v1/public/")) {
            UUID id = jdbc.sql("SELECT id FROM commerce.api_keys WHERE key_hash = :h AND tenant_id = :t AND revoked_at IS NULL").param("h", ApiKeyService.hash(key.trim())).param("t", t.id()).query(UUID.class).optional().orElse(null);
            if (id != null) {
                jdbc.sql("UPDATE commerce.api_keys SET last_used_at = now() WHERE id = :i AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')").param("i", id).update();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null, List.of(new SimpleGrantedAuthority("api.read"))));
            }
        }
        chain.doFilter(req, res);
    }
}

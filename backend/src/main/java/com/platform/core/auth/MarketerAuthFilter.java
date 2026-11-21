package com.platform.core.auth;

import com.platform.affiliate.MarketerAccounts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A marketer's {@code X-Marketer-Token}, on the marketer endpoints only: the request then acts as that marketer (authority ROLE_MARKETER). Anything else gives nothing. */
public class MarketerAuthFilter extends OncePerRequestFilter {
    public record Marketer(UUID id) {}
    private final MarketerAccounts accounts;

    public MarketerAuthFilter(MarketerAccounts accounts) { this.accounts = accounts; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String token = req.getHeader("X-Marketer-Token");
        if (token != null && !token.isBlank() && req.getRequestURI().startsWith("/api/v1/marketer/")) {
            UUID id = accounts.authenticate(token);
            SecurityContextHolder.getContext().setAuthentication(id == null ? null : new UsernamePasswordAuthenticationToken(new Marketer(id), null, List.of(new SimpleGrantedAuthority("ROLE_MARKETER"))));
        }
        chain.doFilter(req, res);
    }
}

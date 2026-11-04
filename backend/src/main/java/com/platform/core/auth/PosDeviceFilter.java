package com.platform.core.auth;

import com.platform.commerce.PosDeviceService;
import com.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A registered till terminal's {@code X-Pos-Device} token, on that shop's own address and only for the terminal's own endpoints (its data and its sync): the request then
 * acts as that terminal (authority {@code pos.device}), whoever is or is not signed in on the browser. A revoked or foreign token gives nothing.
 */
public class PosDeviceFilter extends OncePerRequestFilter {
    private static final List<String> PATHS = List.of("/api/v1/store/pos/terminal/", "/api/v1/store/pos/sync/");
    private final PosDeviceService devices;

    public PosDeviceFilter(PosDeviceService devices) { this.devices = devices; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String token = req.getHeader("X-Pos-Device");
        TenantContext.Current t = TenantContext.get();
        if (token != null && !token.isBlank() && t != null && PATHS.stream().anyMatch(p -> req.getRequestURI().startsWith(p))) {
            var d = devices.authenticate(t.id(), token);
            SecurityContextHolder.getContext().setAuthentication(d == null ? null : new UsernamePasswordAuthenticationToken(d, null, List.of(new SimpleGrantedAuthority("pos.device"))));
        }
        chain.doFilter(req, res);
    }
}

package com.platform.core.tenant;

import com.platform.shared.PlatformProperties;
import com.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves Host -> tenant (spec 5.3). The tenant is derived only from the trusted host, never from
 * a request parameter or body. Unknown / platform hosts simply get no tenant context.
 */
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {
    private final TenantDirectory directory;
    private final PlatformProperties props;

    public TenantResolutionFilter(TenantDirectory directory, PlatformProperties props) {
        this.directory = directory;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String host = requestHost(req);
            if (host != null && !props.isPlatformHost(host) && !props.isOpenHost(host)) {
                directory.resolve(host).ifPresent(TenantContext::set);
                TenantContext.Current c = TenantContext.get();
                if (c != null && "SUSPENDED".equals(c.status()) && !allowedWhileDisabled(req)) {
                    res.setStatus(403);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"status\":403,\"code\":\"TENANT_DISABLED\",\"message\":\"This business is currently disabled\"}");
                    return;
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    /** A disabled business is closed to everyone but its staff: sign in, see the subscription and ask to pay - nothing else. */
    private static boolean allowedWhileDisabled(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) return false;
        boolean get = "GET".equals(req.getMethod());
        if (path.startsWith("/api/v1/auth/") && !path.startsWith("/api/v1/auth/client/")) return true;
        if (path.equals("/api/v1/billing/status") || path.equals("/api/v1/billing/plans") || path.equals("/api/v1/billing/pay-requests")) return true;
        if (get && (path.startsWith("/api/v1/tenant/context") || path.startsWith("/api/v1/tenant/resolve") || path.startsWith("/api/v1/tenant/public") || path.startsWith("/api/v1/tenant/branding"))) return true;
        return get && (path.startsWith("/api/v1/me/") || path.startsWith("/api/v1/notifications"));   // the owner can still read why the business was switched off
    }

    public String requestHost(HttpServletRequest req) {
        String h = null;
        if (props.trustForwardedHost()) h = req.getHeader("X-Forwarded-Host");
        if (h == null || h.isBlank()) h = req.getHeader("Host");
        if (h == null) return null;
        h = h.split(",")[0].trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);
        return h;
    }
}

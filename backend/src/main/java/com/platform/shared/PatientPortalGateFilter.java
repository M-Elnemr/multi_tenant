package com.platform.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** While patient accounts are off, the patient-facing endpoints answer 404 (they do not exist, as far as callers can tell). */
@Component
public class PatientPortalGateFilter extends OncePerRequestFilter {
    private final PatientPortalPolicy policy;

    public PatientPortalGateFilter(PatientPortalPolicy policy) { this.policy = policy; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String p = req.getRequestURI();
        if (!policy.enabled() && (p.startsWith("/api/v1/portal/") || p.equals("/api/v1/clinic/portal/link"))) {
            res.setStatus(404);
            res.setContentType("application/json");
            res.getWriter().write("{\"title\":\"Not Found\",\"status\":404,\"code\":\"PORTAL_DISABLED\",\"message\":\"Patient accounts are not available\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}

package com.platform.analytics;

import com.platform.shared.ClientInfo;
import com.platform.shared.Page;
import com.platform.shared.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class VisitController {
    private final VisitService visits;

    public VisitController(VisitService visits) { this.visits = visits; }

    /** {@code type} is "view" (a page was opened) or "leave" (the visitor left it; {@code durationMs} is the time spent). */
    public record Collect(String type, String id, String visitorId, String sessionId, String path, String query, String referrer, String language,
                          String timezone, Integer screenW, Integer screenH, String userAgent, Long durationMs) {}

    private static UUID uuid(String s) {
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Public and best-effort: bad input is dropped silently so a broken beacon never shows an error to a visitor. */
    @PostMapping("/api/v1/analytics/collect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void collect(@RequestBody Collect c, HttpServletRequest req) {
        UUID id = uuid(c.id()), visitor = uuid(c.visitorId());
        if (id == null || visitor == null) return;
        if ("leave".equals(c.type())) {
            if (c.durationMs() != null) visits.leave(id, visitor, c.durationMs());
            return;
        }
        UUID session = uuid(c.sessionId());
        if (session == null) return;
        TenantContext.Current t = TenantContext.get();
        String host = t != null ? t.host() : req.getHeader("X-Forwarded-Host") != null ? req.getHeader("X-Forwarded-Host") : req.getServerName();
        String country = first(req, "CF-IPCountry", "X-Vercel-IP-Country", "X-Country");
        String city = first(req, "CF-IPCity", "X-Vercel-IP-City", "X-City");
        visits.record(new VisitService.View(id, visitor, session, host, t == null ? null : t.id(), c.path(), c.query(), c.referrer(), ClientInfo.ip(req),
                country, city, c.userAgent(), c.language(), c.timezone(), c.screenW(), c.screenH()));
    }

    private static String first(HttpServletRequest req, String... names) {
        for (String n : names) {
            String v = req.getHeader(n);
            if (v != null && !v.isBlank() && !"XX".equals(v)) return v;
        }
        return null;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    @GetMapping("/api/v1/platform/analytics/visitors")
    @PreAuthorize("hasAuthority('platform.analytics.read')")
    public Map<String, Object> visitors(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) UUID tenantId,
                                        @RequestParam(required = false) String path, @RequestParam(required = false) String country, @RequestParam(required = false) String device,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return visits.visitors(blank(from), blank(to), tenantId, blank(path), blank(country), blank(device), Page.of(page, pageSize));
    }

    @GetMapping("/api/v1/platform/analytics/visitors/{visitorId}")
    @PreAuthorize("hasAuthority('platform.analytics.read')")
    public Map<String, Object> timeline(@PathVariable UUID visitorId) { return visits.timeline(visitorId); }

    @GetMapping("/api/v1/platform/analytics/summary")
    @PreAuthorize("hasAuthority('platform.analytics.read')")
    public Map<String, Object> summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) { return visits.summary(blank(from), blank(to)); }
}

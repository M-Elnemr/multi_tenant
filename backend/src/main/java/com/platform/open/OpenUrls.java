package com.platform.open;

import com.platform.shared.PlatformProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Pictures live on each shop's own host; the marketplace hands clients absolute links so they never need to know which shop a picture belongs to. */
@Component
public class OpenUrls {
    private final JdbcClient jdbc;
    private final PlatformProperties props;
    private final String scheme;
    private final String port;

    /** scheme/port are only ever changed for a local run (http, 3000); in production shops are served on plain https. */
    public OpenUrls(JdbcClient jdbc, PlatformProperties props, @Value("${app.platform.public-scheme:https}") String scheme, @Value("${app.platform.public-port:}") String port) {
        this.jdbc = jdbc;
        this.props = props;
        this.scheme = scheme;
        this.port = port == null || port.isBlank() ? "" : ":" + port.trim();
    }

    /** tenant id -> the shop's public host (its verified primary domain, else slug.root-domain). */
    public Map<UUID, String> hosts(Collection<UUID> tenantIds) {
        Map<UUID, String> out = new HashMap<>();
        if (tenantIds.isEmpty()) return out;
        for (var r : jdbc.sql("""
                SELECT t.id, t.slug, (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary AND d.is_verified LIMIT 1) AS host
                FROM core.tenants t WHERE t.id IN (:ids)
                """).param("ids", tenantIds).query().listOfRows())
            out.put((UUID) r.get("id"), r.get("host") != null ? (String) r.get("host") : props.subdomainHost((String) r.get("slug")));
        return out;
    }

    public String baseUrl(String host) { return scheme + "://" + host + port; }

    /** A product picture at the given size (thumb | medium | original), from the public bucket when the picture is there, else through the shop's file route. */
    public String image(String host, String mediaBase, String mediaExt, String url, String variant) {
        if (mediaBase != null && mediaExt != null && !mediaBase.isBlank()) return baseUrl(host) + mediaBase + "-" + variant + "." + mediaExt;
        if (url == null || url.isBlank()) return null;
        // the same route the shop's own pages use for pictures that are not in the public bucket (the website serves it on every shop host)
        if (url.startsWith("/")) { String u = url.startsWith("/api/v1/") ? "/api/bff/" + url.substring("/api/v1/".length()) : url; return baseUrl(host) + u + (u.contains("?") ? "&" : "?") + "variant=" + variant; }
        return url;
    }

    public Map<String, Object> images(String host, String mediaBase, String mediaExt, String url) {
        String thumb = image(host, mediaBase, mediaExt, url, "thumb");
        if (thumb == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thumb", thumb);
        m.put("medium", image(host, mediaBase, mediaExt, url, "medium"));
        return m;
    }

    public String logo(String host, Object logoFileId) {
        return logoFileId == null ? null : baseUrl(host) + "/api/bff/files/" + logoFileId + "/content?variant=thumb";
    }
}

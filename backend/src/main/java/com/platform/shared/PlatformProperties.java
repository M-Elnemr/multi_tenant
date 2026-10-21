package com.platform.shared;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.platform")
public record PlatformProperties(
        String rootDomain,
        List<String> extraHosts,
        boolean trustForwardedHost,
        String edgeHost,
        int trialDays) {

    public boolean isPlatformHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals(rootDomain) || h.equals("www." + rootDomain) || h.equals("app." + rootDomain)
                || (extraHosts != null && extraHosts.stream().anyMatch(e -> e.trim().equalsIgnoreCase(h)));
    }

    /** The marketplace (Open Store) lives at open.<root-domain>; it is not a tenant, so it gets no tenant context. */
    public boolean isOpenHost(String host) {
        return host != null && host.equalsIgnoreCase("open." + rootDomain);
    }

    public String subdomainHost(String slug) {
        return slug + "." + rootDomain;
    }
}

package com.platform.integrations;

import java.net.InetAddress;
import java.net.URI;

/** Owners type the address of their own store; we must never be turned into a way to reach our own network. Only public https hosts are allowed. */
public final class UrlGuard {
    private UrlGuard() {}

    /** Returns the cleaned base URL (scheme + host [+ port], no trailing slash) or throws a 400 the owner can read. */
    public static String requirePublicHttps(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new HttpGateway.ApiException(400, "Enter the address of your store");
        if (!s.contains("://")) s = "https://" + s;
        URI u;
        try { u = URI.create(s); } catch (IllegalArgumentException e) { throw new HttpGateway.ApiException(400, "The address looks wrong"); }
        String host = u.getHost();
        if (host == null || !"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null) throw new HttpGateway.ApiException(400, "Use an https address such as https://my-store.com");
        host = host.toLowerCase();
        if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) throw new HttpGateway.ApiException(400, "This address cannot be used");
        try {
            for (InetAddress a : InetAddress.getAllByName(host))
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress() || isUniqueLocalV6(a))
                    throw new HttpGateway.ApiException(400, "This address cannot be used");
        } catch (java.net.UnknownHostException e) {
            throw new HttpGateway.ApiException(400, "We could not find this address");
        }
        return "https://" + host + (u.getPort() > 0 && u.getPort() != 443 ? ":" + u.getPort() : "");
    }

    private static boolean isUniqueLocalV6(InetAddress a) { byte[] b = a.getAddress(); return b.length == 16 && (b[0] & 0xfe) == 0xfc; }
}

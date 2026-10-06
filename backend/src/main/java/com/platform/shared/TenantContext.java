package com.platform.shared;

import java.util.UUID;

/** Request-scoped tenant, resolved server-side from the Host header only (spec section 5.3). */
public final class TenantContext {
    public record Current(UUID id, String slug, String type, String status, String host) {}

    private static final ThreadLocal<Current> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Current c) { HOLDER.set(c); }
    public static Current get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    /** The current tenant, or a 404 if the request is not on a tenant host. */
    public static Current require() {
        Current c = HOLDER.get();
        if (c == null) throw BusinessException.notFound("TENANT_NOT_FOUND", "No tenant for this host");
        return c;
    }
}

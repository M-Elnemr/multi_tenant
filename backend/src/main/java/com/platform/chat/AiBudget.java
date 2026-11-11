package com.platform.chat;

/**
 * The time one customer reply may take, shared by every model call made for it on this thread: the gateway in front of the backend gives up at 60 seconds, so a slow model must not
 * be retried, reviewed or asked to rewrite once most of that is gone. Outside a reply (background jobs) there is no limit.
 */
final class AiBudget {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.UUID> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<int[]> HEDGES = new ThreadLocal<>();
    /** A reply may send a slow call a second time once; a model that is slow all the time must not double every call of the reply. */
    static final int HEDGES_PER_REPLY = 1;

    private AiBudget() {}

    /** Starts a reply that must be done within {@code millis}. */
    static void start(long millis) { DEADLINE.set(System.nanoTime() + millis * 1_000_000); HEDGES.set(new int[] {0}); }

    /** The shop this reply is for, so a call that is billed after the reply is over (the losing twin of a hedge) still lands on the right shop. */
    static void forTenant(java.util.UUID tenantId) { TENANT.set(tenantId); }

    static java.util.UUID tenant() { return TENANT.get(); }

    /** True while this reply has not used its twin yet (see {@link #HEDGES_PER_REPLY}); false outside a reply, where nothing is ever hedged. */
    static boolean takeHedge() {
        int[] h = HEDGES.get();
        if (h == null || h[0] >= HEDGES_PER_REPLY) return false;
        h[0]++;
        return true;
    }

    static void clear() { DEADLINE.remove(); TENANT.remove(); HEDGES.remove(); }

    /** Milliseconds left for this reply, or {@link Long#MAX_VALUE} outside one. */
    static long remainingMs() {
        Long d = DEADLINE.get();
        return d == null ? Long.MAX_VALUE : (d - System.nanoTime()) / 1_000_000;
    }

    /** True while at least {@code millis} are left (always outside a reply). */
    static boolean hasLeft(long millis) { return remainingMs() >= millis; }
}

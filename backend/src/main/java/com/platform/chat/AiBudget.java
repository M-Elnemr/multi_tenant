package com.platform.chat;

/**
 * The time one customer reply may take, shared by every model call made for it on this thread: the gateway in front of the backend gives up at 60 seconds, so a slow model must not
 * be retried, reviewed or asked to rewrite once most of that is gone. Outside a reply (background jobs) there is no limit.
 */
final class AiBudget {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.UUID> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<int[]> HEDGES = new ThreadLocal<>();
    private static final ThreadLocal<Long> CALL_CAP = new ThreadLocal<>();
    private static final ThreadLocal<String> REASONING = new ThreadLocal<>();
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

    /**
     * Until {@link #uncapCalls()}: no model call on this thread waits longer than {@code ms}, is retried, or is sent twice. For a step that must never hold the customer's reply (the review):
     * when it is slow or fails, the reply goes out without it.
     */
    static void capCalls(long ms) { CALL_CAP.set(ms); }

    static void uncapCalls() { CALL_CAP.remove(); }

    /** The longest one call may wait under {@link #capCalls}, or {@link Long#MAX_VALUE} when not capped. */
    static long callCapMs() { Long c = CALL_CAP.get(); return c == null ? Long.MAX_VALUE : c; }

    static boolean callsCapped() { return CALL_CAP.get() != null; }

    /** Until {@link #clearReasoning()}: calls on this thread to a model that takes a reasoning level use {@code effort} instead of their own (the review is a short check, not a conversation). */
    static void reasoning(String effort) { if (effort == null || effort.isBlank()) REASONING.remove(); else REASONING.set(effort.trim()); }

    static void clearReasoning() { REASONING.remove(); }

    static String reasoningOverride() { return REASONING.get(); }

    static void clear() { DEADLINE.remove(); TENANT.remove(); HEDGES.remove(); CALL_CAP.remove(); REASONING.remove(); }

    /** Milliseconds left for this reply, or {@link Long#MAX_VALUE} outside one. */
    static long remainingMs() {
        Long d = DEADLINE.get();
        return d == null ? Long.MAX_VALUE : (d - System.nanoTime()) / 1_000_000;
    }

    /** True while at least {@code millis} are left (always outside a reply). */
    static boolean hasLeft(long millis) { return remainingMs() >= millis; }
}

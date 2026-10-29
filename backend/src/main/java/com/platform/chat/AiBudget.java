package com.platform.chat;

/**
 * The time one customer reply may take, shared by every model call made for it on this thread: the gateway in front of the backend gives up at 60 seconds, so a slow model must not
 * be retried, reviewed or asked to rewrite once most of that is gone. Outside a reply (background jobs) there is no limit.
 */
final class AiBudget {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    private AiBudget() {}

    /** Starts a reply that must be done within {@code millis}. */
    static void start(long millis) { DEADLINE.set(System.nanoTime() + millis * 1_000_000); }

    static void clear() { DEADLINE.remove(); }

    /** Milliseconds left for this reply, or {@link Long#MAX_VALUE} outside one. */
    static long remainingMs() {
        Long d = DEADLINE.get();
        return d == null ? Long.MAX_VALUE : (d - System.nanoTime()) / 1_000_000;
    }

    /** True while at least {@code millis} are left (always outside a reply). */
    static boolean hasLeft(long millis) { return remainingMs() >= millis; }
}

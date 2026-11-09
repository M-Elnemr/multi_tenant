package com.platform.chat.unofficial;

import java.util.UUID;

/**
 * Sends a Messenger / Instagram message to a contact OUTSIDE Meta's 24 h window, the way a person would by hand, through an UNOFFICIAL route (Meta's official API forbids this).
 * On by default ({@code app.meta.unofficial.enabled}, an operator kill switch). One implementation per platform; {@link UnofficialMetaService} picks the first sender that {@link #handles} the platform
 * (so a Messenger transport can be plugged in later without touching the policy layer).
 *
 * Implementations never throw: every outcome is a typed {@link Result}. They never log or return credentials.
 */
public interface OutOfWindowSender {
    enum Kind { SENT, BLOCKED, SESSION_EXPIRED, NOT_FOUND, FAILED }

    /** BLOCKED = action block / feedback_required / challenge / checkpoint / rate limit: the caller must stop. */
    record Result(Kind kind, String detail) {
        public static Result sent() { return new Result(Kind.SENT, null); }
        public static Result of(Kind k, String detail) { return new Result(k, detail == null ? null : detail.length() > 190 ? detail.substring(0, 190) : detail); }
        public boolean ok() { return kind == Kind.SENT; }
    }

    /** What the transport says about the shop's session. {@code state}: ACTIVE, NEEDS_LOGIN, UNREACHABLE, UNSUPPORTED or NONE. */
    record SessionStatus(String state, String accountName, String detail) {
        public boolean active() { return "ACTIVE".equals(state); }
    }

    /** ok with the account name, or {@code error}: CHALLENGE, BAD_CREDENTIALS, UNREACHABLE, UNSUPPORTED, FAILED. */
    record LoginResult(boolean ok, String accountName, String error) {
        public static LoginResult ok(String account) { return new LoginResult(true, account, null); }
        public static LoginResult error(String code) { return new LoginResult(false, null, code); }
    }

    /** A 1:1 Messenger thread of the Page: the contact's user id, name and the time of the last activity. */
    record ContactThread(String id, String name, long lastActivityMs, int unread) {}

    /** One page of the Page's Messenger threads; {@code error}: NEEDS_LOGIN, RATE_LIMITED, BRIDGE_UNREACHABLE, UNSUPPORTED, FAILED. {@code nextBefore} null = no more. */
    record ThreadPage(boolean ok, String error, java.util.List<ContactThread> threads, String nextBefore) {
        public static ThreadPage of(java.util.List<ContactThread> t, String next) { return new ThreadPage(true, null, t, next); }
        public static ThreadPage error(String code) { return new ThreadPage(false, code, java.util.List.of(), null); }
    }

    /** A follower of the Page: numeric Facebook user id, name, optional subtitle (city / work) and vanity name (null when the profile is profile.php?id=...). */
    record Follower(String id, String name, String subtitle, String vanity) {}

    /** One run of the Page's followers; {@code error}: NEEDS_LOGIN, RATE_LIMITED, DOCID_STALE, PARSE_EMPTY, BRIDGE_UNREACHABLE, UNSUPPORTED, FAILED. {@code nextCursor} null = the list ended. */
    record FollowerPage(boolean ok, String error, java.util.List<Follower> users, String nextCursor, String collection) {
        public static FollowerPage of(java.util.List<Follower> u, String next, String collection) { return new FollowerPage(true, null, u, next, collection); }
        public static FollowerPage error(String code) { return new FollowerPage(false, code, java.util.List.of(), null, null); }
    }

    /** The Page's followers through the session (Messenger only); the first call has no cursor and no collection, later calls pass back what the previous one returned. */
    default FollowerPage listFollowers(UUID tenant, String platform, int limit, String cursor, String collection) { return FollowerPage.error("UNSUPPORTED"); }

    /** Lists existing contacts through the session (Messenger only). */
    default ThreadPage listThreads(UUID tenant, String platform, int limit, String before) { return ThreadPage.error("UNSUPPORTED"); }

    /** True when this sender serves the platform (FACEBOOK or INSTAGRAM). */
    boolean handles(String platform);

    /** False for a transport that exists only as a stub (Messenger today): the platform is then hidden from the picker. */
    default boolean supported() { return true; }

    /** Is the transport reachable at all (the sidecar's health check, before each send). */
    boolean healthy();

    /**
     * Sends one message. {@code typingMs}: the transport should mark the thread seen and show typing for about that long before sending.
     * {@code recipientRef}: the contact's username when known, otherwise the id Meta gave us.
     */
    Result send(UUID tenant, String platform, String recipientRef, String text, int typingMs);

    default Result send(UUID tenant, String platform, String recipientRef, String text) { return send(tenant, platform, recipientRef, text, 0); }

    SessionStatus status(UUID tenant, String platform);

    LoginResult login(UUID tenant, String platform, String username, String password, String totp);

    /** Cookie-based login (Messenger): the pasted cookie string plus the Page id the session acts as. Transports that log in with a password say UNSUPPORTED. */
    default LoginResult loginWithCookies(UUID tenant, String platform, String cookies, String pageId) { return LoginResult.error("UNSUPPORTED"); }

    /** Drops the session on the transport side (idempotent). */
    void logout(UUID tenant, String platform);
}

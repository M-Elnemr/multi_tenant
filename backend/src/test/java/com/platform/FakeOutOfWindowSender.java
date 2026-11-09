package com.platform;

import com.platform.chat.unofficial.OutOfWindowSender;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Stands in for the Instagram sidecar in tests (Messenger keeps its real "unsupported" stub). Every send is recorded; the test chooses what comes back.
 * Nothing here ever touched a real Instagram account: the whole manual-style route is verified against this fake only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FakeOutOfWindowSender implements OutOfWindowSender {
    public record Sent(UUID tenant, String platform, String ref, String text, int typingMs) {}

    public static final List<Sent> SENT = new CopyOnWriteArrayList<>();
    public static final List<UUID> LOGOUTS = new CopyOnWriteArrayList<>();
    public static final List<String> LOGINS = new CopyOnWriteArrayList<>();
    public static volatile Kind NEXT = Kind.SENT;
    public static volatile boolean HEALTHY = true;
    public static volatile String LOGIN_ERROR = null;
    public static volatile String STATE = "ACTIVE";
    /** When true this fake also stands in for the Messenger sidecar (otherwise the real FbBridgeSender, which is unconfigured in tests, serves FACEBOOK). */
    public static volatile boolean HANDLE_FB = false;

    /** Pages the next listThreads calls return, in order (an empty list = no more threads); THREADS_ERROR makes every call fail with that code. */
    public static final List<ThreadPage> THREAD_PAGES = new CopyOnWriteArrayList<>();
    public static volatile String THREADS_ERROR = null;

    public static void reset() { THREAD_PAGES.clear(); THREADS_ERROR = null; SENT.clear(); LOGOUTS.clear(); LOGINS.clear(); NEXT = Kind.SENT; HEALTHY = true; LOGIN_ERROR = null; STATE = "ACTIVE"; HANDLE_FB = false; }

    @Override public boolean handles(String platform) { return "INSTAGRAM".equals(platform) || (HANDLE_FB && "FACEBOOK".equals(platform)); }

    @Override
    public LoginResult loginWithCookies(UUID tenant, String platform, String cookies, String pageId) {
        LOGINS.add("cookies:" + pageId);
        return LOGIN_ERROR == null ? LoginResult.ok("page_" + pageId) : LoginResult.error(LOGIN_ERROR);
    }
    @Override
    public ThreadPage listThreads(UUID tenant, String platform, int limit, String before) {
        if (THREADS_ERROR != null) return ThreadPage.error(THREADS_ERROR);
        return THREAD_PAGES.isEmpty() ? ThreadPage.of(List.of(), null) : THREAD_PAGES.remove(0);
    }

    @Override public boolean healthy() { return HEALTHY; }

    @Override
    public Result send(UUID tenant, String platform, String recipientRef, String text, int typingMs) {
        if (NEXT == Kind.SENT) { SENT.add(new Sent(tenant, platform, recipientRef, text, typingMs)); return Result.sent(); }
        return Result.of(NEXT, NEXT.name().toLowerCase());
    }

    @Override public SessionStatus status(UUID tenant, String platform) { return new SessionStatus(STATE, "shop_account", null); }

    @Override
    public LoginResult login(UUID tenant, String platform, String username, String password, String totp) {
        LOGINS.add(username);
        return LOGIN_ERROR == null ? LoginResult.ok(username) : LoginResult.error(LOGIN_ERROR);
    }

    @Override public void logout(UUID tenant, String platform) { LOGOUTS.add(tenant); }
}

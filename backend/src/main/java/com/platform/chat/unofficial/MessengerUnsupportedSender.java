package com.platform.chat.unofficial;

import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fallback for Messenger when no real transport serves it ({@link FbBridgeSender} is preferred, order 50): every call says UNSUPPORTED.
 * The policy and plumbing exist, so a real transport can replace this class later.
 */
@Component
@Order(100)
public class MessengerUnsupportedSender implements OutOfWindowSender {
    @Override public boolean handles(String platform) { return "FACEBOOK".equals(platform); }
    @Override public boolean supported() { return false; }
    @Override public boolean healthy() { return false; }
    @Override public Result send(UUID tenant, String platform, String recipientRef, String text, int typingMs) { return Result.of(Kind.FAILED, "UNSUPPORTED"); }
    @Override public SessionStatus status(UUID tenant, String platform) { return new SessionStatus("UNSUPPORTED", null, null); }
    @Override public LoginResult login(UUID tenant, String platform, String username, String password, String totp) { return LoginResult.error("UNSUPPORTED"); }
    @Override public void logout(UUID tenant, String platform) { /* nothing to drop */ }
}

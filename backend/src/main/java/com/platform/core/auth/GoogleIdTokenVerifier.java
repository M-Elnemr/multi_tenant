package com.platform.core.auth;

/** Checks a Google sign-in credential (an ID token) and tells us who signed in. Replaced by a fake in tests. */
public interface GoogleIdTokenVerifier {
    record GoogleIdentity(String sub, String email, String name) {}

    /** The Google client id this server accepts tokens for, or null when Google sign-in is not configured. */
    String clientId();

    /** @throws com.platform.shared.BusinessException 401 when the token is invalid, expired or made for another app */
    GoogleIdentity verify(String idToken);
}

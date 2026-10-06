package com.platform.shared;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientInfo {
    private ClientInfo() {}

    public static String ip(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}

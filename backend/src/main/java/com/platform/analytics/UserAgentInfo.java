package com.platform.analytics;

import java.util.regex.Pattern;

/** Browser, OS and device class from a User-Agent string; deliberately small and dependency-free. */
record UserAgentInfo(String browser, String os, String deviceType, boolean bot) {
    private static final Pattern BOT = Pattern.compile("bot|crawl|spider|slurp|headless|lighthouse|preview|facebookexternalhit|curl|wget|python-requests|monitor", Pattern.CASE_INSENSITIVE);

    static UserAgentInfo parse(String ua) {
        if (ua == null || ua.isBlank()) return new UserAgentInfo("Unknown", "Unknown", "desktop", true);
        String u = ua.toLowerCase();
        String browser = u.contains("edg/") ? "Edge" : u.contains("opr/") || u.contains("opera") ? "Opera" : u.contains("samsungbrowser") ? "Samsung Internet"
                : u.contains("firefox/") || u.contains("fxios") ? "Firefox" : u.contains("chrome/") || u.contains("crios") ? "Chrome"
                : u.contains("safari/") ? "Safari" : "Other";
        String os = u.contains("windows") ? "Windows" : u.contains("android") ? "Android" : u.contains("iphone") || u.contains("ipad") || u.contains("ipod") ? "iOS"
                : u.contains("mac os") || u.contains("macintosh") ? "macOS" : u.contains("linux") ? "Linux" : "Other";
        String device = u.contains("ipad") || u.contains("tablet") || (u.contains("android") && !u.contains("mobile")) ? "tablet"
                : u.contains("mobi") || u.contains("iphone") ? "mobile" : "desktop";
        return new UserAgentInfo(browser, os, device, BOT.matcher(ua).find());
    }
}

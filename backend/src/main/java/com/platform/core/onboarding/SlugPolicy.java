package com.platform.core.onboarding;

import com.platform.shared.BusinessException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SlugPolicy {
    private static final Pattern VALID = Pattern.compile("^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$");
    private static final Set<String> RESERVED = Set.of(
            "www", "app", "api", "admin", "platform", "edge", "mail", "smtp", "ftp", "static", "assets", "cdn", "support",
            "help", "docs", "status", "blog", "dashboard", "login", "register", "signup", "pricing", "stores", "doctors",
            "clinics", "internal", "localhost", "root", "system", "billing", "pay", "payments", "auth", "oauth", "cname");

    private SlugPolicy() {}

    public static String normalize(String raw) {
        if (raw == null) throw BusinessException.badRequest("INVALID_SLUG", "Slug is required");
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Throws if the slug cannot be used; returns it normalized otherwise. */
    public static String validate(String raw) {
        String s = normalize(raw);
        if (!VALID.matcher(s).matches() || s.contains("--"))
            throw BusinessException.badRequest("INVALID_SLUG", "Use 3-40 lowercase letters, digits or hyphens");
        if (RESERVED.contains(s)) throw BusinessException.badRequest("SLUG_RESERVED", "This name is reserved");
        return s;
    }
}

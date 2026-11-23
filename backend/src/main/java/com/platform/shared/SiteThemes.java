package com.platform.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-site colour theme (design revolution; mirrors web/lib/theme.ts). A site's theme is {"tokens": {"primary":"#rrggbb", ...}}.
 * Only whitelisted tokens, only #rrggbb hex, a contrast floor on the two pairs that carry the copy and the buttons, and a hard size cap:
 * nothing else can ever reach the public stylesheet. Reads are also re-validated so a hand-edited row can never inject CSS.
 */
public final class SiteThemes {
    public static final List<String> TOKENS = List.of("primary", "secondary", "accent", "bg", "bg2", "surface", "text", "text2", "btnBg", "btnText", "border");
    private static final Set<String> TOKEN_SET = Set.copyOf(TOKENS);
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    /** Hard floor (WCAG large-text level). The editor warns earlier, at 4.5:1. */
    public static final double MIN_CONTRAST = 3.0;
    private static final int MAX_JSON_CHARS = 1024;
    private static final String[][] PAIRS = {{"text", "bg"}, {"btnText", "btnBg"}};
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private SiteThemes() {}

    /** Validates a request value. Returns the JSON text to store, or null to clear the theme (null or no tokens). Problems go to {@code fe} under siteTheme.* paths. */
    public static String clean(Object input, FieldErrors fe) {
        if (input == null) return null;
        if (!(input instanceof Map<?, ?> m)) { fe.add("siteTheme", "Send the colours as { tokens: { ... } }"); return null; }
        for (Object k : m.keySet()) if (!"tokens".equals(k)) fe.add("siteTheme", "Only { tokens } is allowed");
        Object t = m.get("tokens");
        if (t == null) return null;
        if (!(t instanceof Map<?, ?> tokens)) { fe.add("siteTheme.tokens", "Send the colours as { tokens: { ... } }"); return null; }
        if (tokens.size() > TOKENS.size()) fe.add("siteTheme.tokens", "Too many colours");
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : tokens.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!TOKEN_SET.contains(key)) { fe.add("siteTheme.tokens." + sanitizeKey(key), "Unknown colour"); continue; }
            if (!(e.getValue() instanceof String v) || !HEX.matcher(v).matches()) { fe.add("siteTheme.tokens." + key, "Use a colour like #1a2b3c"); continue; }
            out.put(key, v.toLowerCase());
        }
        for (String[] p : PAIRS) {
            String a = out.get(p[0]), b = out.get(p[1]);
            if (a != null && b != null && contrast(a, b) < MIN_CONTRAST)
                fe.add("siteTheme.tokens." + p[0], "These two colours are too close to read (" + p[0] + " on " + p[1] + "); pick colours that stand apart");
        }
        if (!fe.isEmpty() || out.isEmpty()) return null;
        try {
            String s = JSON.writeValueAsString(Map.of("tokens", ordered(out)));
            if (s.length() > MAX_JSON_CHARS) { fe.add("siteTheme", "Theme is too large"); return null; }
            return s;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { fe.add("siteTheme", "Invalid value"); return null; }
    }

    /** What the API returns: null when the site has no custom colours, else {tokens:{...}} with only valid entries. */
    public static Map<String, Object> read(Object jsonText) {
        if (jsonText == null) return null;
        Map<String, Object> raw;
        try { raw = Rows.json(jsonText); } catch (RuntimeException e) { return null; }
        if (!(raw.get("tokens") instanceof Map<?, ?> t)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : TOKENS) if (t.get(k) instanceof String v && HEX.matcher(v).matches()) out.put(k, v.toLowerCase());
        return out.isEmpty() ? null : Map.of("tokens", out);
    }

    private static Map<String, String> ordered(Map<String, String> in) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String k : TOKENS) if (in.containsKey(k)) o.put(k, in.get(k));
        return o;
    }

    private static String sanitizeKey(String k) { return (k.length() > 24 ? k.substring(0, 24) : k).replaceAll("[^A-Za-z0-9_]", "_"); }

    private static double lum(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    public static double contrast(String a, String b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}

package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Small readers for the loosely typed JSON the platforms return. */
final class J {
    private J() {}

    static String s(JsonNode n, String... path) {
        JsonNode c = n;
        for (String p : path) c = c == null ? null : c.path(p);
        return c == null || c.isMissingNode() || c.isNull() ? null : (c.isValueNode() ? c.asText() : c.toString());
    }

    static String sOr(JsonNode n, String fallback, String... path) { String v = s(n, path); return v == null || v.isBlank() ? fallback : v; }

    static long minor(JsonNode n, String... path) {
        String v = s(n, path);
        try { return v == null || v.isBlank() ? 0 : com.platform.integrations.ChannelAdapter.minor(v); } catch (NumberFormatException e) { return 0; }
    }

    static Long minorOrNull(JsonNode n, String... path) {
        String v = s(n, path);
        try { return v == null || v.isBlank() ? null : com.platform.integrations.ChannelAdapter.minor(v); } catch (NumberFormatException e) { return null; }
    }

    static Integer intOrNull(JsonNode n, String... path) {
        String v = s(n, path);
        try { return v == null || v.isBlank() ? null : (int) Math.round(Double.parseDouble(v)); } catch (NumberFormatException e) { return null; }
    }

    static Instant time(JsonNode n, String... path) {
        String v = s(n, path);
        if (v == null || v.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(v).toInstant(); } catch (Exception ignored) { /* try the next format */ }
        try { return Instant.parse(v); } catch (Exception ignored) { /* try the next format */ }
        try { return java.time.LocalDateTime.parse(v.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC); } catch (Exception ignored) { return null; }
    }

    static List<JsonNode> list(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n != null && n.isArray()) n.forEach(out::add);
        return out;
    }

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null && !p.isBlank()) { if (sb.length() > 0) sb.append(' '); sb.append(p.trim()); }
        return sb.toString();
    }

    static String html(String text) { return text == null ? "" : "<p>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</p>"; }

    /** Description coming back from a platform as HTML, as plain text. */
    static String plain(String html) { return html == null ? "" : html.replaceAll("<br\\s*/?>", "\n").replaceAll("</p>", "\n").replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim(); }
}

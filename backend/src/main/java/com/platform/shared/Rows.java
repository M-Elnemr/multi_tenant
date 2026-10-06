package com.platform.shared;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.json.JsonParserFactory;

/** Turns raw JDBC rows into API-shaped maps (snake_case -> camelCase, timestamps -> Instant). */
public final class Rows {
    private Rows() {}

    public static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(camelKey(k), value(v)));
        return out;
    }

    public static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(camel(r));
        return out;
    }

    /** Parses a JSON text column (selected with ::text) into a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> json(Object text) {
        if (text == null) return Map.of();
        return JsonParserFactory.getJsonParser().parseMap(text.toString());
    }

    /** Parses a JSON array text column (selected with ::text) into a list. */
    public static List<Object> jsonList(Object text) {
        if (text == null) return List.of();
        return JsonParserFactory.getJsonParser().parseList(text.toString());
    }

    private static Object value(Object v) {
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        return v;
    }

    static String camelKey(String k) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : k.toCharArray()) {
            if (c == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }
}

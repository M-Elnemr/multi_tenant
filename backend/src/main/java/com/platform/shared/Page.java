package com.platform.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paging per spec 25/68: default 20, max 100, never unbounded. */
public record Page(int page, int pageSize) {
    public static Page of(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        return new Page(p, s);
    }

    public int offset() { return (page - 1) * pageSize; }

    public Map<String, Object> wrap(List<?> data, long total) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page);
        meta.put("pageSize", pageSize);
        meta.put("total", total);
        meta.put("hasNext", (long) page * pageSize < total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("meta", meta);
        return out;
    }
}

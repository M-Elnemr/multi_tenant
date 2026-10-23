package com.platform.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outbound HTTP to the platforms. One seam so tests replace the network with canned answers. */
public interface HttpGateway {
    ObjectMapper JSON = new ObjectMapper();

    /** {@code rateKey} groups calls that share one platform limit (the gateway spaces them out and obeys Retry-After / 429). */
    record Req(String method, String url, Map<String, String> headers, String body, String rateKey, Duration timeout) {
        public static Req get(String url, Map<String, String> headers, String rateKey) { return new Req("GET", url, headers, null, rateKey, Duration.ofSeconds(30)); }
        public static Req json(String method, String url, Map<String, String> headers, Object body, String rateKey) {
            try {
                Map<String, String> h = new LinkedHashMap<>(headers);
                h.putIfAbsent("Content-Type", "application/json");
                return new Req(method, url, h, body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body)), rateKey, Duration.ofSeconds(30));
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        public static Req form(String url, Map<String, String> headers, Map<String, String> fields, String rateKey) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            fields.forEach((k, v) -> { if (sb.length() > 0) sb.append('&'); sb.append(java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8)).append('=').append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)); });
            return new Req("POST", url, h, sb.toString(), rateKey, Duration.ofSeconds(30));
        }
    }

    record Res(int status, String body, Map<String, List<String>> headers) {
        public boolean ok() { return status >= 200 && status < 300; }

        public JsonNode json() {
            try { return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body); } catch (Exception e) { return JSON.createObjectNode(); }
        }

        public String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
    }

    Res send(Req req);

    /** The platform refused or failed: carries the HTTP status and a short message safe to show the owner. */
    class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }

    /** Sends and throws {@link ApiException} unless the answer is 2xx. */
    default Res call(Req req) {
        Res r = send(req);
        if (!r.ok()) throw new ApiException(r.status(), describe(r));
        return r;
    }

    /** First useful message in an error body (platforms differ: message / error / errors[0] ...), clipped. */
    static String describe(Res r) {
        JsonNode j = r.json();
        for (String k : new String[] {"message", "error_description", "error", "errors", "detail", "Message", "ErrorMessage"}) {
            JsonNode n = j.path(k);
            if (n.isMissingNode() || n.isNull()) continue;
            String t = n.isTextual() ? n.asText() : (n.isArray() && n.size() > 0 ? (n.get(0).isTextual() ? n.get(0).asText() : n.get(0).toString()) : n.toString());
            if (!t.isBlank()) return "HTTP " + r.status() + ": " + (t.length() > 300 ? t.substring(0, 300) : t);
        }
        String b = r.body() == null ? "" : r.body().strip();
        if (b.startsWith("<")) b = "";   // a web page, not an answer: the status says enough
        return "HTTP " + r.status() + (b.isEmpty() ? "" : ": " + (b.length() > 200 ? b.substring(0, 200) : b));
    }
}

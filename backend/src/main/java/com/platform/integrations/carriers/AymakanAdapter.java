package com.platform.integrations.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.ChannelAdapter.Ctx;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Aymakan (Saudi Arabia) API v2: the account's security code goes in the Authorization header. Only shipments still in "Submitted" can be cancelled. */
@Component
public class AymakanAdapter implements CarrierAdapter {
    static final String LIVE = "https://api.aymakan.net/v2";
    static final String TEST = "https://dev-api.aymakan.com.sa/v2";

    @Override public String provider() { return "AYMAKAN"; }

    private static String base(Ctx c) { return "yes".equalsIgnoreCase(c.opt("sandbox")) ? TEST : LIVE; }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body) {
        Map<String, String> h = Map.of("Authorization", c.need("apiKey"), "Accept", "application/json");
        HttpGateway.Res r = c.http().send("GET".equals(method) ? Req.get(base(c) + path, h, c.rateKey()) : Req.json(method, base(c) + path, h, body, c.rateKey()));
        if (r.status() == 401) throw new HttpGateway.ApiException(401, "Aymakan did not accept this API key");
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), "Aymakan: " + message(r));
        return r;
    }

    /** 422 answers carry {message, errors:{field:[...]}}; the first field error is the useful part. */
    private static String message(HttpGateway.Res r) {
        JsonNode j = r.json();
        JsonNode errors = j.path("errors");
        if (errors.isObject() && errors.size() > 0) {
            JsonNode first = errors.elements().next();
            return first.isArray() && first.size() > 0 ? first.get(0).asText() : first.asText();
        }
        String m = j.path("message").asText(j.path("response").asText(""));
        return m.isEmpty() ? HttpGateway.describe(r) : m;
    }

    @Override
    public void validate(Ctx c) {
        // /cities is public, so it proves nothing about the key. Tracking a number that cannot exist answers 401 for a wrong key and 422 ("No shipments found") for a right one.
        HttpGateway.Res r = c.http().send(Req.get(base(c) + "/shipping/track/AY0000000000", Map.of("Authorization", c.need("apiKey"), "Accept", "application/json"), c.rateKey()));
        if (r.status() == 401) throw new HttpGateway.ApiException(401, "Aymakan did not accept this API key");
        if (r.status() >= 500 || r.status() == 0) throw new HttpGateway.ApiException(r.status(), "Aymakan: " + HttpGateway.describe(r));
    }

    @Override
    public Created create(Ctx c, ShipRequest r) {
        ObjectNode b = HttpGateway.JSON.createObjectNode();
        b.put("requested_by", orElse(r.sender().name(), "Shop"));
        b.put("declared_value", Math.max(1, Double.parseDouble(ChannelAdapter.amount(Math.max(100, r.codMinor())))));
        b.put("declared_value_currency", cur(r.currency()));
        b.put("reference", r.reference().replaceAll("[^A-Za-z0-9]", ""));
        b.put("is_cod", r.codMinor() > 0 ? 1 : 0);
        if (r.codMinor() > 0) b.put("cod_amount", Double.parseDouble(ChannelAdapter.amount(r.codMinor())));
        b.put("currency", cur(r.currency()));
        party(b, "delivery", r.receiver(), r.notes());
        party(b, "collection", r.sender(), null);
        if (r.weightKg() > 0) b.put("weight", r.weightKg());
        b.put("pieces", Math.max(1, r.pieces()));
        b.put("items_count", Math.max(1, r.pieces()));
        JsonNode s = call(c, "POST", "/shipping/create", b).json().path("shipping");
        String tn = s.path("tracking_number").asText("");
        if (tn.isEmpty()) throw new HttpGateway.ApiException(422, "Aymakan did not return a tracking number");
        String label = s.path("pdf_label").asText(s.path("label").asText(""));
        return new Created(tn, label.isEmpty() ? null : label);
    }

    private static void party(ObjectNode b, String prefix, Party p, String notes) {
        b.put(prefix + "_name", orElse(p.name(), "-"));
        if (p.email() != null && !p.email().isBlank()) b.put(prefix + "_email", p.email());
        b.put(prefix + "_city", orElse(p.city(), "-"));
        b.put(prefix + "_address", orElse(p.line1(), "-"));
        b.put(prefix + "_country", p.country() == null || p.country().isBlank() ? "SA" : p.country());
        b.put(prefix + "_phone", digits(p.phone()));
        if (p.zip() != null && !p.zip().isBlank()) b.put(prefix + "_postcode", p.zip());
        if (notes != null && !notes.isBlank()) b.put(prefix + "_description", notes);
    }

    private static String digits(String phone) { return phone == null ? "" : phone.replaceAll("\\D", ""); }

    private static String orElse(String v, String d) { return v == null || v.isBlank() ? d : v; }

    private static String cur(String c) { return "AED".equals(c) || "USD".equals(c) ? c : "SAR"; }

    @Override
    public Tracking track(Ctx c, String tn) {
        JsonNode j = call(c, "GET", "/shipping/track/" + java.net.URLEncoder.encode(tn, java.nio.charset.StandardCharsets.UTF_8), null).json();
        JsonNode s = j.path("data").path("shipments");
        s = s.isArray() && s.size() > 0 ? s.get(0) : s;
        String status = s.path("status").asText("");
        JsonNode events = s.path("tracking_info");
        String last = events.isArray() && events.size() > 0 ? events.get(0).path("description").asText(status) : status;
        Instant at = null;
        if (events.isArray() && events.size() > 0) {
            try { at = java.time.LocalDateTime.parse(events.get(0).path("created_at").asText().replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC); } catch (Exception ignored) { /* the event time is optional */ }
        }
        return new Tracking(status, CarrierAdapter.stageOf(status + " " + last), last, at);
    }

    @Override
    public void cancel(Ctx c, String tn) {
        HttpGateway.Res r = call(c, "POST", "/shipping/cancel", Map.of("tracking", tn));
        if (r.json().path("error").asBoolean(false)) throw new HttpGateway.ApiException(409, "Aymakan: " + r.json().path("message").asText("this shipment can no longer be cancelled"));
    }
}

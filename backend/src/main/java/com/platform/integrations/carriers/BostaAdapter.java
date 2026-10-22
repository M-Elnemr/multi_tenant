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

/** Bosta (Egypt) delivery API v2: the business API key goes in the Authorization header. Cash on delivery is passed in pounds. */
@Component
public class BostaAdapter implements CarrierAdapter {
    static final String BASE = "https://app.bosta.co/api/v2";

    @Override public String provider() { return "BOSTA"; }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body) {
        Map<String, String> h = Map.of("Authorization", c.need("apiKey"), "Accept", "application/json");
        HttpGateway.Res r = c.http().send("GET".equals(method) ? Req.get(BASE + path, h, c.rateKey()) : Req.json(method, BASE + path, h, body, c.rateKey()));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), "Bosta: " + HttpGateway.describe(r));
        return r;
    }

    @Override
    public void validate(Ctx c) {
        HttpGateway.Res r = c.http().send(Req.get(BASE + "/pickup-locations", Map.of("Authorization", c.need("apiKey"), "Accept", "application/json"), c.rateKey()));
        if (r.status() == 401 || r.status() == 403) throw new HttpGateway.ApiException(401, "Bosta did not accept this API key");
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), "Bosta: " + HttpGateway.describe(r));
    }

    @Override
    public Created create(Ctx c, ShipRequest r) {
        ObjectNode b = HttpGateway.JSON.createObjectNode();
        b.put("type", 10);   // deliver a package to a customer
        ObjectNode specs = b.putObject("specs");
        specs.put("packageType", "Parcel");
        specs.putObject("packageDetails").put("itemsCount", Math.max(1, r.pieces())).put("description", r.description() == null ? "" : r.description());
        b.put("cod", r.codMinor() > 0 ? Double.parseDouble(ChannelAdapter.amount(r.codMinor())) : 0);
        ObjectNode drop = b.putObject("dropOffAddress");
        drop.put("city", r.receiver().city() == null ? "" : r.receiver().city());
        drop.put("firstLine", r.receiver().line1() == null ? "" : r.receiver().line1());
        if (r.receiver().line2() != null && !r.receiver().line2().isBlank()) drop.put("secondLine", r.receiver().line2());
        String[] name = (r.receiver().name() == null ? "Customer" : r.receiver().name().trim()).split("\\s+", 2);
        ObjectNode rc = b.putObject("receiver");
        rc.put("firstName", name[0]); rc.put("lastName", name.length > 1 ? name[1] : "."); rc.put("phone", r.receiver().phone() == null ? "" : r.receiver().phone().replaceFirst("^\\+20", "0"));
        if (r.receiver().email() != null && !r.receiver().email().isBlank()) rc.put("email", r.receiver().email());
        b.put("businessReference", r.reference());
        if (r.notes() != null && !r.notes().isBlank()) b.put("notes", r.notes());
        JsonNode res = call(c, "POST", "/deliveries", b).json();
        String tn = res.path("trackingNumber").asText(res.path("data").path("trackingNumber").asText(""));
        if (tn.isEmpty()) throw new HttpGateway.ApiException(422, "Bosta did not return a tracking number");
        return new Created(tn, null);
    }

    @Override
    public Tracking track(Ctx c, String tn) {
        JsonNode r = call(c, "GET", "/deliveries/business/" + java.net.URLEncoder.encode(tn, java.nio.charset.StandardCharsets.UTF_8), null).json();
        JsonNode d = r.has("data") ? r.path("data") : r;
        String state = d.path("state").path("value").asText(d.path("state").isTextual() ? d.path("state").asText() : "");
        return new Tracking(state, CarrierAdapter.stageOf(state), state, null);
    }

    @Override
    public void cancel(Ctx c, String tn) {
        JsonNode r = call(c, "GET", "/deliveries/business/" + java.net.URLEncoder.encode(tn, java.nio.charset.StandardCharsets.UTF_8), null).json();
        JsonNode d = r.has("data") ? r.path("data") : r;
        String id = d.path("_id").asText("");
        if (id.isEmpty()) throw new HttpGateway.ApiException(404, "Bosta could not find this delivery");
        call(c, "DELETE", "/deliveries/business/" + id, null);
    }
}

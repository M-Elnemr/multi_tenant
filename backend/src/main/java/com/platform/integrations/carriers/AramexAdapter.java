package com.platform.integrations.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter.Ctx;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Aramex Shipping and Tracking JSON services (Egypt, Saudi Arabia, UAE and the world). The owner enters the API credentials Aramex gave them
 * (a "sandbox" flag points to Aramex's test servers). Cancel is done with Aramex directly: its API has no cancel call.
 */
@Component
public class AramexAdapter implements CarrierAdapter {
    private static final Pattern DATE = Pattern.compile("/Date\\((-?\\d+)");

    @Override public String provider() { return "ARAMEX"; }

    private static String host(Ctx c) { return "yes".equalsIgnoreCase(c.opt("sandbox")) ? "https://ws.dev.aramex.net" : "https://ws.aramex.net"; }

    private ObjectNode clientInfo(Ctx c) {
        ObjectNode i = HttpGateway.JSON.createObjectNode();
        i.put("UserName", c.need("userName")); i.put("Password", c.need("password")); i.put("Version", "v1.0"); i.put("AccountNumber", c.need("accountNumber")); i.put("AccountPin", c.need("accountPin"));
        i.put("AccountEntity", c.need("accountEntity")); i.put("AccountCountryCode", c.need("accountCountryCode")); i.put("Source", 24);
        return i;
    }

    private JsonNode post(Ctx c, String path, ObjectNode body) {
        HttpGateway.Res r = c.http().send(Req.json("POST", host(c) + path, Map.of("Accept", "application/json"), body, c.rateKey()));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
        return r.json();
    }

    private static String notifications(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode x : n.path("Notifications")) { if (sb.length() > 0) sb.append("; "); sb.append(x.path("Message").asText(x.path("Code").asText())); }
        return sb.length() == 0 ? "Aramex refused the request" : sb.toString();
    }

    @Override
    public void validate(Ctx c) {
        ObjectNode b = HttpGateway.JSON.createObjectNode();
        b.set("ClientInfo", clientInfo(c));
        b.put("Code", c.need("accountCountryCode"));
        JsonNode r = post(c, "/ShippingAPI.V2/Location/Service_1_0.svc/json/FetchCountry", b);
        if (r.path("HasErrors").asBoolean(false)) throw new HttpGateway.ApiException(401, "Aramex did not accept these credentials (" + notifications(r) + ")");
    }

    private static ObjectNode party(CarrierAdapter.Party p, String account, String ref) {
        ObjectNode o = HttpGateway.JSON.createObjectNode();
        o.put("Reference1", ref);
        if (account != null) o.put("AccountNumber", account);
        ObjectNode a = o.putObject("PartyAddress");
        a.put("Line1", p.line1() == null ? "" : p.line1()); a.put("Line2", p.line2() == null ? "" : p.line2()); a.put("Line3", ""); a.put("City", p.city() == null ? "" : p.city());
        a.put("StateOrProvinceCode", p.state() == null ? "" : p.state()); a.put("PostCode", p.zip() == null ? "" : p.zip()); a.put("CountryCode", p.country());
        ObjectNode c = o.putObject("Contact");
        c.put("PersonName", p.name()); c.put("CompanyName", p.name()); c.put("PhoneNumber1", p.phone() == null ? "" : p.phone()); c.put("PhoneNumber1Ext", ""); c.put("CellPhone", p.phone() == null ? "" : p.phone());
        c.put("EmailAddress", p.email() == null ? "" : p.email()); c.put("Type", "");
        return o;
    }

    private static String msDate(Instant t) { return "/Date(" + t.toEpochMilli() + ")/"; }

    @Override
    public Created create(Ctx c, ShipRequest r) {
        boolean domestic = r.sender().country().equalsIgnoreCase(r.receiver().country());
        ObjectNode shipment = HttpGateway.JSON.createObjectNode();
        shipment.put("Reference1", r.reference());
        shipment.set("Shipper", party(r.sender(), c.need("accountNumber"), r.reference()));
        shipment.set("Consignee", party(r.receiver(), null, r.reference()));
        shipment.put("ShippingDateTime", msDate(Instant.now().plusSeconds(3600)));
        shipment.put("DueDate", msDate(Instant.now().plusSeconds(3 * 86400)));
        ObjectNode d = shipment.putObject("Details");
        d.putNull("Dimensions");
        d.putObject("ActualWeight").put("Unit", "KG").put("Value", Math.max(0.1, r.weightKg()));
        d.put("DescriptionOfGoods", r.description() == null || r.description().isBlank() ? "Goods" : r.description());
        d.put("GoodsOriginCountry", r.sender().country());
        d.put("NumberOfPieces", Math.max(1, r.pieces()));
        d.put("ProductGroup", domestic ? "DOM" : "EXP");
        d.put("ProductType", domestic ? "ONP" : "PPX");
        d.put("PaymentType", "P");
        d.put("PaymentOptions", "");
        if (r.codMinor() > 0) {
            d.put("Services", "CODS");
            d.putObject("CashOnDeliveryAmount").put("CurrencyCode", r.currency()).put("Value", Double.parseDouble(com.platform.integrations.ChannelAdapter.amount(r.codMinor())));
        } else d.put("Services", "");
        ObjectNode body = HttpGateway.JSON.createObjectNode();
        body.set("ClientInfo", clientInfo(c));
        body.putObject("LabelInfo").put("ReportID", 9201).put("ReportType", "URL");
        ArrayNode list = body.putArray("Shipments");
        list.add(shipment);
        JsonNode res = post(c, "/ShippingAPI.V2/Shipping/Service_1_0.svc/json/CreateShipments", body);
        JsonNode s = res.path("Shipments").isArray() && res.path("Shipments").size() > 0 ? res.path("Shipments").get(0) : null;
        if (res.path("HasErrors").asBoolean(false) || s == null || s.path("HasErrors").asBoolean(false) || s.path("ID").asText("").isEmpty())
            throw new HttpGateway.ApiException(422, "Aramex: " + notifications(s != null && s.path("HasErrors").asBoolean(false) ? s : res));
        String label = s.path("ShipmentLabel").path("LabelURL").asText("");
        return new Created(s.path("ID").asText(), label.isEmpty() ? null : label);
    }

    @Override
    public Tracking track(Ctx c, String trackingNumber) {
        ObjectNode b = HttpGateway.JSON.createObjectNode();
        b.set("ClientInfo", clientInfo(c));
        b.put("GetLastTrackingUpdateOnly", true);
        b.putArray("Shipments").add(trackingNumber);
        JsonNode r = post(c, "/ShippingAPI.V2/Tracking/Service_1_0.svc/json/TrackShipments", b);
        for (JsonNode e : r.path("TrackingResults")) {
            JsonNode last = e.path("Value").isArray() && e.path("Value").size() > 0 ? e.path("Value").get(0) : null;
            if (last == null) continue;
            String desc = last.path("UpdateDescription").asText("");
            Matcher m = DATE.matcher(last.path("UpdateDateTime").asText(""));
            return new Tracking(desc, CarrierAdapter.stageOf(desc), desc + (last.path("UpdateLocation").asText("").isEmpty() ? "" : " - " + last.path("UpdateLocation").asText()), m.find() ? Instant.ofEpochMilli(Long.parseLong(m.group(1))) : null);
        }
        return new Tracking("Created", "CREATED", null, null);
    }

    @Override
    public void cancel(Ctx c, String trackingNumber) { throw new HttpGateway.ApiException(400, "Aramex shipments are cancelled with Aramex directly (their API has no cancel call)"); }
}

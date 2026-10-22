package com.platform.integrations.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.ChannelAdapter.Ctx;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Mylerz (Egypt) integration API: a token from the account's username and password, then orders are added to the pickup warehouse. Tracking is not read through the API yet. */
@Component
public class MylerzAdapter implements CarrierAdapter {
    static final String BASE = "https://integration.mylerz.net";

    @Override public String provider() { return "MYLERZ"; }

    private String token(Ctx c) {
        HttpGateway.Res r = c.http().send(Req.form(BASE + "/token", Map.of("Accept", "application/json"), Map.of("grant_type", "password", "username", c.need("username"), "password", c.need("password")), c.rateKey()));
        if (!r.ok() || r.json().path("access_token").asText("").isEmpty()) throw new HttpGateway.ApiException(401, "Mylerz did not accept these credentials");
        return r.json().path("access_token").asText();
    }

    @Override public void validate(Ctx c) { token(c); }

    @Override
    public Created create(Ctx c, ShipRequest r) {
        ArrayNode orders = HttpGateway.JSON.createArrayNode();
        ObjectNode o = orders.addObject();
        o.put("WarehouseName", c.need("warehouseName"));
        o.put("PickupDueDate", java.time.OffsetDateTime.now().plusDays(1).toString());
        o.put("Package_Serial", 1);
        o.put("Description", r.description() == null ? "Goods" : r.description());
        o.put("Total_Weight", Math.max(0.1, r.weightKg()));
        o.put("Service_Type", "DTD");
        o.put("Service", "Delivery");
        o.put("Service_Category", "DELIVERY");
        o.put("Payment_Type", r.codMinor() > 0 ? "COD" : "PP");
        o.put("COD_Value", r.codMinor() > 0 ? Double.parseDouble(ChannelAdapter.amount(r.codMinor())) : 0);
        o.put("Customer_Name", r.receiver().name());
        o.put("Mobile_No", r.receiver().phone() == null ? "" : r.receiver().phone().replaceFirst("^\\+20", "0"));
        o.put("Street", r.receiver().line1() == null ? "" : r.receiver().line1());
        o.put("City", r.receiver().city() == null ? "" : r.receiver().city());
        o.put("Country", "Egypt");
        o.put("Currency", r.currency());
        o.put("Reference", r.reference());
        HttpGateway.Res res = c.http().send(Req.json("POST", BASE + "/api/Orders/AddOrders", Map.of("Authorization", "Bearer " + token(c), "Accept", "application/json"), orders, c.rateKey()));
        if (!res.ok()) throw new HttpGateway.ApiException(res.status(), "Mylerz: " + HttpGateway.describe(res));
        JsonNode j = res.json();
        if (j.path("IsErrorState").asBoolean(false)) throw new HttpGateway.ApiException(422, "Mylerz: " + j.path("ErrorDescription").asText("the order was refused"));
        JsonNode pkg = j.path("Value").path("Packages");
        String barcode = pkg.isArray() && pkg.size() > 0 ? pkg.get(0).path("BarCode").asText(pkg.get(0).path("Barcode").asText("")) : "";
        if (barcode.isEmpty()) throw new HttpGateway.ApiException(422, "Mylerz did not return a barcode");
        return new Created(barcode, null);
    }

    @Override public Tracking track(Ctx c, String trackingNumber) { return null; }

    @Override public void cancel(Ctx c, String trackingNumber) { throw new HttpGateway.ApiException(400, "Cancel Mylerz shipments in the Mylerz portal"); }
}

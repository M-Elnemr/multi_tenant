package com.platform.integrations.carriers;

import com.platform.integrations.ChannelAdapter.Ctx;
import java.time.Instant;

/** One shipping company. Adapters are stateless; the account's credentials arrive in the {@link Ctx}. */
public interface CarrierAdapter {
    record Party(String name, String phone, String email, String line1, String line2, String city, String state, String country, String zip) {}

    /** {@code codMinor} is the cash the courier collects (0 = prepaid). */
    record ShipRequest(String reference, Party sender, Party receiver, long codMinor, String currency, double weightKg, int pieces, String description, String notes) {}

    record Created(String trackingNumber, String labelUrl) {}

    /** stage: CREATED | PICKED_UP | IN_TRANSIT | OUT_FOR_DELIVERY | DELIVERED | RETURNED | CANCELLED | EXCEPTION */
    record Tracking(String status, String stage, String lastEvent, Instant at) {}

    String provider();

    /** Checks the credentials with the carrier; throws {@link com.platform.integrations.HttpGateway.ApiException} when they are wrong. */
    void validate(Ctx ctx);

    Created create(Ctx ctx, ShipRequest r);

    /** Null when this carrier gives no tracking through its API. */
    Tracking track(Ctx ctx, String trackingNumber);

    void cancel(Ctx ctx, String trackingNumber);

    /** Words in a carrier status mapped to our stages (carriers phrase these differently). */
    static String stageOf(String text) {
        String t = text == null ? "" : text.toLowerCase();
        if (t.contains("deliver") && !t.contains("out for") && !t.contains("attempt") && !t.contains("failed") && !t.contains("undeliver")) return "DELIVERED";
        if (t.contains("out for delivery") || t.contains("with courier") || t.contains("out for")) return "OUT_FOR_DELIVERY";
        if (t.contains("return") || t.contains("rto")) return "RETURNED";
        if (t.contains("cancel") || t.contains("terminat")) return "CANCELLED";
        if (t.contains("fail") || t.contains("exception") || t.contains("attempt") || t.contains("hold") || t.contains("undeliver")) return "EXCEPTION";
        if (t.contains("pick") || t.contains("collect") || t.contains("receiv")) return "PICKED_UP";
        if (t.contains("transit") || t.contains("hub") || t.contains("arriv") || t.contains("depart") || t.contains("sorting") || t.contains("warehouse")) return "IN_TRANSIT";
        return "CREATED";
    }
}

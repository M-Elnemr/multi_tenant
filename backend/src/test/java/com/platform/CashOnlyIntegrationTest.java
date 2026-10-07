package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Platform default: card payments are switched off. Everything must work with cash only. */
@TestPropertySource(properties = "app.payments.card-enabled=false")
class CashOnlyIntegrationTest extends IntegrationTestBase {
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void storeRejectsCardEverywhereAndTracksCashToCollect() throws Exception {
        Tenant store = onboard("STORE");
        String h = store.host();
        // card cannot be enabled, and is not even listed
        onHost(h, store.access(), "PUT", "/api/v1/store/payment-methods", "{\"method\":\"CARD\",\"enabled\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        onHost(h, store.access(), "GET", "/api/v1/store/payment-methods", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].method").value("CASH_ON_DELIVERY"));
        onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.paymentMethods.length()").value(1));

        String branch = JsonPath.read(body(onHost(h, store.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String variant = JsonPath.read(body(onHost(h, store.access(), "POST", "/api/v1/store/products",
                "{\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String cust = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/shop/customers/register", "{\"firstName\":\"Nada\",\"phone\":\"%s\",\"password\":\"shopperPass1\"}".formatted(nextPhone())).andExpect(status().isCreated())), "$.accessToken");
        String ship = JsonPath.<List<String>>read(body(onHost(h, null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[?(@.type=='PICKUP')].id").get(0);
        String order = "{\"items\":[{\"variantId\":\"%s\",\"quantity\":2}],\"shippingMethodId\":\"%s\",\"paymentMethod\":\"%s\"}";
        onHost(h, cust, "POST", "/api/v1/shop/checkout", order.formatted(variant, ship, "CARD")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        String id = JsonPath.read(body(onHost(h, cust, "POST", "/api/v1/shop/checkout", order.formatted(variant, ship, "CASH_ON_DELIVERY")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"))), "$.orderId");

        // cash still to collect shows up on the dashboard until the order is delivered
        onHost(h, store.access(), "GET", "/api/v1/store/reports/summary", null).andExpect(jsonPath("$.cashToCollectCount").value(1)).andExpect(jsonPath("$.cashToCollectMinor").value(20000)).andExpect(jsonPath("$.cashCollectedTodayMinor").value(0));
        for (String s : List.of("PROCESSING", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED"))
            onHost(h, store.access(), "POST", "/api/v1/store/orders/" + id + "/status", "{\"status\":\"" + s + "\"}").andExpect(status().isOk());
        onHost(h, store.access(), "GET", "/api/v1/store/reports/summary", null).andExpect(jsonPath("$.cashToCollectMinor").value(0)).andExpect(jsonPath("$.cashCollectedTodayMinor").value(20000));
    }

    @Test
    void clinicIsCashOnlyAndShowsUnpaidVisits() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        onHost(h, c.access(), "PATCH", "/api/v1/clinic/profile", "{\"cardEnabled\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.cardEnabled").value(false)).andExpect(jsonPath("$.cardAvailable").value(false));
        onHost(h, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.cardEnabled").value(false));

        String doctor = JsonPath.read(body(onHost(h, c.access(), "GET", "/api/v1/clinic/doctors", null)), "$[0].id");
        String branch = JsonPath.read(body(onHost(h, c.access(), "GET", "/api/v1/clinic/branches", null)), "$[0].id");
        String service = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/services", "{\"name\":\"Paid\",\"durationMinutes\":30,\"priceMinor\":30000}").andExpect(status().isCreated())), "$.id");
        String phone = nextPhone();
        String reg = body(onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Rim\",\"phone\":\"%s\",\"initialPassword\":\"CashPass1234\"}".formatted(phone)).andExpect(status().isCreated()));
        String patient = JsonPath.read(reg, "$.id");
        String token = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"CashPass1234\"}".formatted(phone))), "$.accessToken");
        LocalDate d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(3);
        while (d.getDayOfWeek().getValue() == 5 || d.getDayOfWeek().getValue() == 6) d = d.plusDays(1);
        String slot = JsonPath.<List<String>>read(body(onHost(h, null, "GET", "/api/v1/clinic/public/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(doctor, branch, service, d), null)), "$").get(0);
        String base = "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"".formatted(patient, doctor, branch, service, slot);
        onHost(h, token, "POST", "/api/v1/portal/appointments", base + ",\"paymentMethod\":\"CARD\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        String appt = JsonPath.read(body(onHost(h, token, "POST", "/api/v1/portal/appointments", base + "}").andExpect(status().isCreated()).andExpect(jsonPath("$.paymentMethod").value("CASH_AT_CLINIC"))), "$.id");

        onHost(h, c.access(), "GET", "/api/v1/clinic/dashboard", null).andExpect(jsonPath("$.unpaidVisitsCount").value(0));
        onHost(h, c.access(), "POST", "/api/v1/clinic/appointments/" + appt + "/check-in", "{}").andExpect(status().isOk());
        var dash = body(onHost(h, c.access(), "GET", "/api/v1/clinic/dashboard", null));
        assertThat((Integer) JsonPath.read(dash, "$.unpaidVisitsCount")).isEqualTo(1);
        assertThat(((Number) JsonPath.read(dash, "$.unpaidVisitsMinor")).longValue()).isEqualTo(30000L);
        onHost(h, c.access(), "POST", "/api/v1/clinic/appointments/" + appt + "/mark-paid", "{}").andExpect(jsonPath("$.paymentStatus").value("PAID"));
        onHost(h, c.access(), "GET", "/api/v1/clinic/dashboard", null).andExpect(jsonPath("$.unpaidVisitsCount").value(0));
    }
}

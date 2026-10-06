package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.BillingService;
import com.platform.notifications.NotificationListeners;
import com.platform.notifications.NotificationService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class NotificationsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired NotificationService notifications;
    @Autowired NotificationListeners listeners;
    @Autowired BillingService billing;

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    List<String> types(String host, String token) throws Exception {
        return JsonPath.read(body(onHost(host, token, "GET", "/api/v1/notifications", null).andExpect(status().isOk())), "$.data[*].notificationType");
    }

    @Test
    void ordersNotifyShopperAndStaffWithEmailOutboxLowStockAndOwnInboxOnly() throws Exception {
        Tenant store = onboard("STORE");
        String h = store.host();
        String branch = JsonPath.read(body(onHost(h, store.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String variant = JsonPath.read(body(onHost(h, store.access(), "POST", "/api/v1/store/products",
                "{\"name\":\"Lamp %s\",\"variants\":[{\"sku\":\"L-%s\",\"priceMinor\":5000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String email = "shopper" + uniq() + "@example.com";
        String shopper = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/shop/customers/register",
                "{\"firstName\":\"Salma\",\"phone\":\"%s\",\"email\":\"%s\",\"password\":\"shopperPass1\"}".formatted(nextPhone(), email)).andExpect(status().isCreated())), "$.accessToken");
        String ship = JsonPath.<List<String>>read(body(onHost(h, null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[?(@.type=='PICKUP')].id").get(0);

        String order = body(onHost(h, shopper, "POST", "/api/v1/shop/checkout",
                "{\"items\":[{\"variantId\":\"%s\",\"quantity\":3}],\"shippingMethodId\":\"%s\",\"paymentMethod\":\"CASH_ON_DELIVERY\"}".formatted(variant, ship)).andExpect(status().isCreated()));
        String orderId = JsonPath.read(order, "$.orderId");

        assertThat(types(h, shopper)).contains("ORDER_CREATED");
        assertThat(types(h, store.access())).contains("NEW_ORDER", "LOW_STOCK");   // 5 -> 2 crosses the threshold of 3
        // the shopper does not receive the merchant's alerts, and vice versa
        assertThat(types(h, shopper)).doesNotContain("NEW_ORDER", "LOW_STOCK");

        onHost(h, store.access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"PROCESSING\"}").andExpect(status().isOk());
        assertThat(types(h, shopper)).contains("ORDER_STATUS");

        // e-mail goes through the outbox and is sent by the background job
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE to_address = :e AND status = 'PENDING'").param("e", email).query(Long.class).single()).isGreaterThanOrEqualTo(2);
        notifications.dispatchOutbox();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE to_address = :e AND status = 'SENT'").param("e", email).query(Long.class).single()).isGreaterThanOrEqualTo(2);

        // inbox: unread count, mark read, isolation
        long unread = ((Number) JsonPath.read(body(onHost(h, shopper, "GET", "/api/v1/notifications/unread-count", null)), "$.count")).longValue();
        assertThat(unread).isGreaterThanOrEqualTo(2);
        String first = JsonPath.read(body(onHost(h, shopper, "GET", "/api/v1/notifications", null)), "$.data[0].id");
        onHost(h, store.access(), "POST", "/api/v1/notifications/" + first + "/read", "{}").andExpect(status().isNotFound());   // not theirs
        onHost(h, shopper, "POST", "/api/v1/notifications/" + first + "/read", "{}").andExpect(status().isOk());
        onHost(h, shopper, "POST", "/api/v1/notifications/read-all", "{}").andExpect(status().isOk());
        onHost(h, shopper, "GET", "/api/v1/notifications/unread-count", null).andExpect(jsonPath("$.count").value(0));
        onHost(h, null, "GET", "/api/v1/notifications", null).andExpect(status().isUnauthorized());
    }

    @Test
    void medicalNotificationsAreGenericAndRemindersFireOnce() throws Exception {
        Tenant clinic = onboard("CLINIC");
        String h = clinic.host();
        String doctor = JsonPath.read(body(onHost(h, clinic.access(), "GET", "/api/v1/clinic/doctors", null)), "$[0].id");
        String branch = JsonPath.read(body(onHost(h, clinic.access(), "GET", "/api/v1/clinic/branches", null)), "$[0].id");
        String service = JsonPath.<List<String>>read(body(onHost(h, clinic.access(), "GET", "/api/v1/clinic/services", null)), "$[?(@.name=='Consultation')].id").get(0);

        String phone = nextPhone();
        String reg = body(onHost(h, clinic.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Laila\",\"phone\":\"%s\",\"email\":\"laila%s@example.com\"}".formatted(phone, uniq())).andExpect(status().isCreated()));
        String patientId = JsonPath.read(reg, "$.id");
        String patient = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/auth/activate", "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"PatientPass1\"}".formatted(phone, JsonPath.read(reg, "$.activationPin").toString()))), "$.accessToken");

        LocalDate d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(3);
        while (d.getDayOfWeek().getValue() == 5 || d.getDayOfWeek().getValue() == 6) d = d.plusDays(1);
        List<String> slots = JsonPath.read(body(onHost(h, null, "GET", "/api/v1/clinic/public/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(doctor, branch, service, d), null)), "$");
        // booking requires the doctor's approval -> staff are told; the patient is told once it is confirmed
        onHost(h, clinic.access(), "PATCH", "/api/v1/clinic/profile", "{\"requiresConfirmation\":true}").andExpect(status().isOk());
        String appt = JsonPath.read(body(onHost(h, patient, "POST", "/api/v1/portal/appointments",
                "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(patientId, doctor, branch, service, slots.get(0))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))), "$.id");
        assertThat(types(h, clinic.access())).contains("BOOKING_REQUEST");
        onHost(h, clinic.access(), "POST", "/api/v1/clinic/appointments/" + appt + "/confirm", "{}").andExpect(status().isOk());
        String patientInbox = body(onHost(h, patient, "GET", "/api/v1/notifications", null));
        assertThat(JsonPath.<List<String>>read(patientInbox, "$.data[*].notificationType")).contains("APPOINTMENT_CONFIRMED");
        // privacy: no doctor name, time, service or clinical detail in patient-facing text
        assertThat(patientInbox).doesNotContain("Dr.", "Consultation", slots.get(0));

        // a new prescription produces only the generic "update in your portal" message
        String enc = JsonPath.read(body(onHost(h, clinic.access(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(patientId))), "$.id");
        onHost(h, clinic.access(), "POST", "/api/v1/clinic/encounters/" + enc + "/prescriptions", "{\"items\":[{\"medicationName\":\"SECRET-DRUG-NAME\"}],\"issue\":true}").andExpect(status().isCreated());
        patientInbox = body(onHost(h, patient, "GET", "/api/v1/notifications", null));
        assertThat(JsonPath.<List<String>>read(patientInbox, "$.data[*].notificationType")).contains("PRESCRIPTION_ISSUED");
        assertThat(patientInbox).doesNotContain("SECRET-DRUG-NAME");
        assertThat(jdbc.sql("SELECT body FROM notifications.outbox WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", clinic.slug()).query(String.class).list()).noneMatch(b -> b.contains("SECRET-DRUG-NAME"));

        // reminders: due within 24h, sent once
        jdbc.sql("UPDATE medical.appointments SET start_at = now() + interval '5 hours', end_at = now() + interval '5 hours 30 minutes' WHERE id = :i").param("i", UUID.fromString(appt)).update();
        listeners.appointmentReminders();
        listeners.appointmentReminders();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'APPOINTMENT_REMINDER' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", clinic.slug()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void nonPaymentWarnsTheOwnerWithoutBlockingTheJob() throws Exception {
        Tenant store = onboard("STORE");
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", store.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.tenant_subscriptions SET current_period_end = now() - interval '1 hour' WHERE tenant_id = :t").param("t", tenantId).update();
        billing.expiryCheck();
        assertThat(types(store.host(), store.access())).contains("SUBSCRIPTION_PAST_DUE");
    }
}

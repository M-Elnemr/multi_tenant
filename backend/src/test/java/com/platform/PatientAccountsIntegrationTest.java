package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Default configuration: patient accounts on (created only by the clinic), online booking off. */
class PatientAccountsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired com.platform.notifications.NotificationService notifications;

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void onlyTheClinicRegistersPatientsWithATemporaryPasswordAndBookingIsOff() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        // no self-registration of any kind
        onHost(h, null, "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Self\",\"phone\":\"%s\",\"initialPassword\":\"TempPass123\"}".formatted(nextPhone())).andExpect(status().isUnauthorized());
        onHost(h, null, "POST", "/api/v1/auth/activate", "{\"identifier\":\"%s\",\"pin\":\"12345678\",\"newPassword\":\"Whatever123\"}".formatted(nextPhone())).andExpect(status().isBadRequest());
        // a new mobile needs a temporary password
        String phone = nextPhone();
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Salma\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TEMP_PASSWORD_REQUIRED"));
        PatientLogin p = patientAt(c, "Salma", phone);
        onHost(h, p.token(), "GET", "/api/v1/portal/patients", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(p.patientId()));
        // the old temporary password no longer works, the new one does
        onHost(h, null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"TempPass123\"}".formatted(phone)).andExpect(status().isUnauthorized());
        onHost(h, null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\"}".formatted(phone)).andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false));
        // refresh keeps a patient a patient
        String refresh = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\"}".formatted(phone))), "$.refreshToken");
        String again = JsonPath.read(body(onHost(h, null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh)).andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("PATIENT"))), "$.accessToken");
        onHost(h, again, "GET", "/api/v1/portal/queue", null).andExpect(status().isOk());

        // the patient app signs in once on the platform address and then opens each clinic with the same login
        String platformToken = JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\"}".formatted(phone)).andExpect(status().isOk())), "$.accessToken");
        onHost("platform.test", platformToken, "GET", "/api/v1/me/tenants", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].host").value(h));
        onHost(h, platformToken, "GET", "/api/v1/portal/queue", null).andExpect(status().isOk());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"nope\"}".formatted(phone)).andExpect(status().isUnauthorized());

        // booking is switched off: the public site says so and the portal refuses
        onHost(h, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.bookingEnabled").value(false));
        onHost(h, p.token(), "POST", "/api/v1/portal/appointments", "{\"patientId\":\"%s\"}".formatted(p.patientId())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_DISABLED"));
    }

    @Test
    void patientSeesTheirPlaceInTheQueueAndGetsAPushWhenCalled() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        String doctor = JsonPath.read(body(onHost(h, c.access(), "GET", "/api/v1/clinic/doctors", null)), "$[0].id");
        String branch = JsonPath.read(body(onHost(h, c.access(), "GET", "/api/v1/clinic/branches", null)), "$[0].id");
        PatientLogin first = patientAt(c, "First");
        PatientLogin second = patientAt(c, "Second");
        String walk = "{\"patientId\":\"%s\",\"doctorId\":\"" + doctor + "\",\"branchId\":\"" + branch + "\",\"visitType\":\"CONSULTATION\"}";
        String a1 = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(first.patientId())).andExpect(status().isCreated())), "$.id");
        String a2 = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(second.patientId())).andExpect(status().isCreated())), "$.id");

        // the public clinic page shows how many are waiting
        onHost(h, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.queueCount").value(2));
        // each patient sees only their own place
        onHost(h, second.token(), "GET", "/api/v1/portal/queue", null).andExpect(jsonPath("$[0].aheadOfYou").value(1)).andExpect(jsonPath("$[0].called").value(false));
        assertThat(body(onHost(h, second.token(), "GET", "/api/v1/portal/queue", null))).doesNotContain("First");

        String tokSecond = "fcm-second-" + uniq(), tokFirst = "fcm-first-" + uniq();
        // the second patient's phone registers for push; calling them queues a push for that phone only
        onHost(h, second.token(), "POST", "/api/v1/portal/devices", "{\"token\":\"%s\",\"platform\":\"android\"}".formatted(tokSecond)).andExpect(status().isOk());
        onHost(h, first.token(), "POST", "/api/v1/portal/devices", "{\"token\":\"%s\"}".formatted(tokFirst)).andExpect(status().isOk());
        onHost(h, c.access(), "POST", "/api/v1/clinic/appointments/" + a2 + "/call", "{}").andExpect(status().isOk());
        List<String> pushed = jdbc.sql("SELECT to_address FROM notifications.outbox WHERE channel = 'PUSH' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", c.slug()).query(String.class).list();
        assertThat(pushed).containsExactly(tokSecond);
        // without Firebase configured the sender only logs, and the message is marked sent
        notifications.dispatchOutbox();
        assertThat(jdbc.sql("SELECT status FROM notifications.outbox WHERE channel = 'PUSH' AND to_address = :t").param("t", tokSecond).query(String.class).single()).isEqualTo("SENT");
        onHost(h, second.token(), "GET", "/api/v1/portal/queue", null).andExpect(jsonPath("$[0].called").value(true));
        // unregistering removes the device
        onHost(h, second.token(), "DELETE", "/api/v1/portal/devices", "{\"token\":\"%s\"}".formatted(tokSecond)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.device_tokens WHERE token = :t").param("t", tokSecond).query(Long.class).single()).isZero();
        assertThat(a1).isNotEqualTo(a2);
    }

    @Test
    void clinicDetailsShowDoctorPhoneAddressAndQueueSize() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        onHost(h, c.access(), "PATCH", "/api/v1/clinic/profile", "{\"phone\":\"01099990000\",\"addressText\":\"12 Tahrir St, Cairo\"}").andExpect(status().isOk());
        onHost(h, c.access(), "PATCH", "/api/v1/clinic/doctors/me", "{\"fields\":{\"publicPhone\":\"01088887777\"}}").andExpect(status().isOk()).andExpect(jsonPath("$.publicPhone").value("01088887777"));
        onHost(h, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("01099990000")).andExpect(jsonPath("$.addressText").value("12 Tahrir St, Cairo"))
                .andExpect(jsonPath("$.doctors[0].publicPhone").value("01088887777")).andExpect(jsonPath("$.queueCount").value(0));
    }

    @Test
    void testsAreRequestedAsLabOrRadiologyAndThePatientMarksThemDone() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        PatientLogin p = patientAt(c, "Hoda");
        PatientLogin other = patientAt(c, "Other");
        String enc = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(p.patientId())).andExpect(status().isCreated())), "$.id");
        String lab = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/encounters/" + enc + "/lab-orders", "{\"testName\":\"CBC\",\"kind\":\"LAB\"}").andExpect(status().isCreated())), "$.id");
        String xray = JsonPath.read(body(onHost(h, c.access(), "POST", "/api/v1/clinic/encounters/" + enc + "/lab-orders", "{\"testName\":\"Chest X-ray\",\"kind\":\"RADIOLOGY\"}").andExpect(status().isCreated())), "$.id");
        onHost(h, c.access(), "POST", "/api/v1/clinic/encounters/" + enc + "/lab-orders", "{\"testName\":\"Bad\",\"kind\":\"NOPE\"}").andExpect(status().isBadRequest());

        // the patient's history lists both, as requested
        String tl = body(onHost(h, p.token(), "GET", "/api/v1/portal/patients/" + p.patientId() + "/timeline", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(tl, "$.labOrders[?(@.testName=='Chest X-ray')].kind")).containsExactly("RADIOLOGY");
        assertThat(JsonPath.<List<String>>read(tl, "$.labOrders[*].status")).containsOnly("ORDERED");

        // only the owner can mark it done; once only
        onHost(h, other.token(), "POST", "/api/v1/portal/lab-orders/" + xray + "/done", "{}").andExpect(status().isNotFound());
        onHost(h, p.token(), "POST", "/api/v1/portal/lab-orders/" + xray + "/done", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        onHost(h, p.token(), "POST", "/api/v1/portal/lab-orders/" + xray + "/done", "{}").andExpect(status().isConflict());
        // the doctor sees it in the exam, and the open count includes it
        String exam = body(onHost(h, c.access(), "GET", "/api/v1/clinic/encounters/" + enc, null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(exam, "$.labOrders[?(@.testName=='Chest X-ray')].status")).containsExactly("DONE");
        assertThat(JsonPath.<List<String>>read(exam, "$.labOrders[?(@.testName=='CBC')].status")).containsExactly("ORDERED");
        assertThat(UUID.fromString(lab)).isNotNull();
    }
}

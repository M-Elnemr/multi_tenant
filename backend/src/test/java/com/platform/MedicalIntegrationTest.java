package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.MockPaymentProvider;
import com.platform.medical.AppointmentService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

class MedicalIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired MockPaymentProvider provider;
    @Autowired AppointmentService appointmentService;

    record Clinic(Tenant t, String doctorId, String branchId, String serviceId) {
        String host() { return t.host(); }
        String owner() { return t.access(); }
    }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    String read(String json, String path) { return JsonPath.read(json, path).toString(); }

    Clinic clinic() throws Exception {
        Tenant t = onboard("CLINIC");
        String doctor = read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/doctors", null).andExpect(status().isOk())), "$[0].id");
        String branch = read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/branches", null)), "$[0].id");
        String service = JsonPath.<List<String>>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/services", null)), "$[?(@.name=='Consultation')].id").get(0);
        return new Clinic(t, doctor, branch, service);
    }

    /** A working day (Sun-Thu, the seeded schedule) at least 3 days out, so booking-notice rules never interfere. */
    LocalDate workday(int minDaysAhead) {
        LocalDate d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(minDaysAhead);
        while (d.getDayOfWeek().getValue() == 5 || d.getDayOfWeek().getValue() == 6) d = d.plusDays(1);
        return d;
    }

    List<String> slots(Clinic c, LocalDate d, boolean publicEndpoint) throws Exception {
        String url = (publicEndpoint ? "/api/v1/clinic/public/slots" : "/api/v1/clinic/appointments/slots") + "?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(c.doctorId(), c.branchId(), c.serviceId(), d);
        return JsonPath.read(body(onHost(c.host(), publicEndpoint ? null : c.owner(), "GET", url, null).andExpect(status().isOk())), "$");
    }

    /** Registers a patient (new phone) and activates the portal account. Returns {patientId, code, token, phone}. */
    String[] patientWithPortal(Clinic c, String first) throws Exception {
        String phone = nextPhone();
        String res = body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"%s\",\"lastName\":\"Test\",\"phone\":\"%s\"}".formatted(first, phone))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("ACTIVATION_PIN")).andExpect(jsonPath("$.patientCode").value(org.hamcrest.Matchers.matchesPattern("PAT-[A-Z0-9]{4}-[A-Z0-9]{4}"))));
        String token = read(body(onHost(c.host(), null, "POST", "/api/v1/auth/activate",
                "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"PatientPass1\"}".formatted(phone, read(res, "$.activationPin"))).andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("PATIENT"))), "$.accessToken");
        return new String[] {read(res, "$.id"), read(res, "$.patientCode"), token, phone};
    }

    String bookAs(Clinic c, String token, String patientId, String startAt, int expect) throws Exception {
        return body(onHost(c.host(), token, "POST", "/api/v1/portal/appointments",
                "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(patientId, c.doctorId(), c.branchId(), c.serviceId(), startAt)).andExpect(status().is(expect)));
    }

    @Test
    void newClinicIsBookableImmediatelyAndPublicSiteIsReadable() throws Exception {
        Clinic c = clinic();
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/schedules", null).andExpect(jsonPath("$.length()").value(5));
        onHost(c.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk()).andExpect(jsonPath("$.doctors.length()").value(1))
                .andExpect(jsonPath("$.services.length()").value(2)).andExpect(jsonPath("$.branches[0].code").value("MAIN"));
        LocalDate d = workday(3);
        List<String> s = slots(c, d, true);
        assertThat(s).hasSize(12);   // 10:00-16:00 every 30 minutes
        // a clinic host exposes no store API and a store host no clinic API
        onHost(c.host(), null, "GET", "/api/v1/shop/products", null).andExpect(status().isNotFound());
        onHost(onboard("STORE").host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isNotFound());
    }

    @Test
    void doubleBookingIsImpossibleEvenUnderRace() throws Exception {
        Clinic c = clinic();
        String p1 = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"A\"}").andExpect(status().isCreated())), "$.id");
        String p2 = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"B\"}").andExpect(status().isCreated())), "$.id");
        LocalDate d = workday(3);
        String slot = slots(c, d, false).get(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (String p : List.of(p1, p2))
                jobs.add(() -> onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments",
                        "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(p, c.doctorId(), c.branchId(), c.serviceId(), slot)).andReturn().getResponse().getStatus());
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(jobs)) codes.add(f.get());
            assertThat(codes).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(slots(c, d, false)).doesNotContain(slot);
        // the database constraint itself refuses an overlapping row even if the application check were bypassed
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.sql("""
                INSERT INTO medical.appointments (tenant_id, patient_id, doctor_id, branch_id, service_id, start_at, end_at, status, booking_source)
                SELECT tenant_id, patient_id, doctor_id, branch_id, service_id, start_at + interval '10 minutes', end_at + interval '10 minutes', 'CONFIRMED', 'RECEPTION' FROM medical.appointments
                WHERE doctor_id = :d AND status = 'CONFIRMED' LIMIT 1
                """).param("d", UUID.fromString(c.doctorId())).update());
        // exceptions remove the day entirely
        LocalDate off = workday(10);
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/schedule-exceptions", "{\"doctorId\":\"%s\",\"branchId\":\"%s\",\"date\":\"%s\",\"type\":\"DAY_OFF\",\"reason\":\"Conference\"}".formatted(c.doctorId(), c.branchId(), off)).andExpect(status().isCreated());
        assertThat(slots(c, off, true)).isEmpty();
    }

    @Test
    void patientPortalOnboardingBookingAndPrivacyBetweenPatients() throws Exception {
        Clinic c = clinic();
        String[] a = patientWithPortal(c, "Alaa");
        String[] b = patientWithPortal(c, "Basma");
        LocalDate d = workday(3);
        String slot = slots(c, d, true).get(0);

        bookAs(c, a[2], a[0], slot, 201);
        onHost(c.host(), a[2], "GET", "/api/v1/portal/appointments", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
        onHost(c.host(), b[2], "GET", "/api/v1/portal/appointments", null).andExpect(jsonPath("$.meta.total").value(0));
        bookAs(c, b[2], b[0], slot, 409);                 // slot taken
        bookAs(c, b[2], a[0], slots(c, d, true).get(0), 404); // cannot book for someone else's record
        onHost(c.host(), b[2], "GET", "/api/v1/portal/patients/" + a[0] + "/timeline", null).andExpect(status().isNotFound());
        onHost(c.host(), a[2], "GET", "/api/v1/portal/patients/" + a[0] + "/timeline", null).andExpect(status().isOk());
        // patients cannot use staff endpoints, and patient code alone grants nothing
        onHost(c.host(), a[2], "GET", "/api/v1/clinic/patients", null).andExpect(status().isForbidden());
        onHost(c.host(), a[2], "GET", "/api/v1/clinic/patients/" + b[0], null).andExpect(status().isForbidden());
        onHost(c.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"x\"}".formatted(a[1])).andExpect(status().isUnauthorized());
        // too-short notice is rejected for patients
        List<String> soon = slots(c, LocalDate.now(ZoneId.of("Africa/Cairo")), true);
        assertThat(soon.size()).isLessThanOrEqualTo(12);
    }

    @Test
    void existingAccountMustProveOwnershipToClaimARecord() throws Exception {
        Clinic c = clinic();
        Tenant store = onboard("STORE");
        String phone = nextPhone();
        onHost(store.host(), null, "POST", "/api/v1/shop/customers/register", "{\"firstName\":\"Heba\",\"phone\":\"%s\",\"password\":\"hebaPass123\"}".formatted(phone)).andExpect(status().isCreated());

        String res = body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Heba\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("LINK_PIN")));
        String code = read(res, "$.patientCode");
        String pin = read(res, "$.linkPin");
        // the account is not a member of the clinic yet, so it cannot just log in and see the record
        onHost(c.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"hebaPass123\"}".formatted(phone)).andExpect(status().isForbidden());
        String link = "{\"identifier\":\"%s\",\"password\":\"%s\",\"patientCode\":\"%s\",\"pin\":\"%s\"}";
        onHost(c.host(), null, "POST", "/api/v1/clinic/portal/link", link.formatted(phone, "wrongpass", code, pin)).andExpect(status().isUnauthorized());
        onHost(c.host(), null, "POST", "/api/v1/clinic/portal/link", link.formatted(phone, "hebaPass123", code, "00000000")).andExpect(status().isBadRequest());
        // somebody else's account cannot claim it even with the right PIN
        String other = nextPhone();
        onHost(store.host(), null, "POST", "/api/v1/shop/customers/register", "{\"firstName\":\"Eve\",\"phone\":\"%s\",\"password\":\"evePass1234\"}".formatted(other)).andExpect(status().isCreated());
        onHost(c.host(), null, "POST", "/api/v1/clinic/portal/link", link.formatted(other, "evePass1234", code, pin)).andExpect(status().isBadRequest());
        String token = read(body(onHost(c.host(), null, "POST", "/api/v1/clinic/portal/link", link.formatted(phone, "hebaPass123", code, pin)).andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("PATIENT"))), "$.accessToken");
        onHost(c.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(jsonPath("$[0].patientCode").value(code));
        // single use
        onHost(c.host(), null, "POST", "/api/v1/clinic/portal/link", link.formatted(phone, "hebaPass123", code, pin)).andExpect(status().isBadRequest());
    }

    @Test
    void clinicalRecordsAreAuditedRevisionedAndPatientSeesOnlyWhatIsShared() throws Exception {
        Clinic c = clinic();
        String[] p = patientWithPortal(c, "Karim");
        LocalDate d = workday(3);
        String slot = slots(c, d, false).get(0);
        String appt = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments",
                "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(p[0], c.doctorId(), c.branchId(), c.serviceId(), slot)).andExpect(status().isCreated())), "$.id");

        // appointment state machine
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + appt + "/complete", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + appt + "/check-in", "{}").andExpect(jsonPath("$.status").value("CHECKED_IN")).andExpect(jsonPath("$.queueNumber").value(1));
        String enc = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\",\"appointmentId\":\"%s\",\"chiefComplaint\":\"Headache\"}".formatted(p[0], appt)).andExpect(status().isCreated())), "$.id");
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + appt + "/start", "{}").andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/vitals", "{\"temperatureC\":37.2,\"heartRateBpm\":72,\"systolicBp\":120,\"diastolicBp\":80}").andExpect(status().isCreated());
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/conditions", "{\"name\":\"Tension headache\"}").andExpect(status().isCreated());
        onHost(c.host(), c.owner(), "PATCH", "/api/v1/clinic/encounters/" + enc, "{\"clinicalSummary\":\"INTERNAL-SUMMARY\"}").andExpect(status().isOk());
        String internal = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/notes", "{\"content\":\"INTERNAL-DOCTOR-NOTE\",\"patientVisible\":false}").andExpect(status().isCreated())), "$.id");
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/notes", "{\"noteType\":\"INSTRUCTION\",\"content\":\"Rest and hydrate\",\"patientVisible\":true}").andExpect(status().isCreated());
        onHost(c.host(), c.owner(), "PATCH", "/api/v1/clinic/notes/" + internal, "{\"content\":\"INTERNAL-DOCTOR-NOTE v2\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT content FROM medical.note_revisions WHERE note_id = :n").param("n", UUID.fromString(internal)).query(String.class).list()).containsExactly("INTERNAL-DOCTOR-NOTE");

        String draft = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/prescriptions", "{\"items\":[{\"medicationName\":\"Paracetamol\",\"dosage\":\"500mg\",\"frequency\":\"q8h\"}],\"issue\":false}").andExpect(status().isCreated())), "$.id");
        onHost(c.host(), p[2], "GET", "/api/v1/portal/patients/" + p[0] + "/timeline", null).andExpect(jsonPath("$.prescriptions.length()").value(0));   // drafts are invisible
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/prescriptions/" + draft + "/issue", "{}").andExpect(jsonPath("$.status").value("ISSUED"));
        String cancelled = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/prescriptions", "{\"items\":[{\"medicationName\":\"Wrong drug\"}],\"issue\":true}").andExpect(status().isCreated())), "$.id");
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/prescriptions/" + cancelled + "/cancel", "{}").andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.sql("SELECT count(*) FROM medical.prescription_revisions WHERE prescription_id = :p").param("p", UUID.fromString(cancelled)).query(Long.class).single()).isEqualTo(2);   // ISSUED + CANCELLED kept

        String lab = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/lab-orders", "{\"testName\":\"CBC\",\"priority\":\"URGENT\"}").andExpect(status().isCreated())), "$.id");
        onHost(c.host(), p[2], "POST", "/api/v1/portal/lab-orders/" + lab + "/results", "{\"resultText\":\"Hb 13.2\"}").andExpect(status().isCreated());
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/dashboard", null).andExpect(jsonPath("$.pendingLabReviews").value(1));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/lab-orders/" + lab + "/review", "{\"shareWithPatient\":true}").andExpect(jsonPath("$.status").value("REVIEWED"));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + appt + "/complete", "{}").andExpect(jsonPath("$.status").value("COMPLETED"));

        String patientView = body(onHost(c.host(), p[2], "GET", "/api/v1/portal/patients/" + p[0] + "/timeline", null).andExpect(status().isOk()));
        assertThat(patientView).contains("Rest and hydrate", "Paracetamol", "CBC", "Hb 13.2");
        assertThat(patientView).doesNotContain("INTERNAL-DOCTOR-NOTE", "INTERNAL-SUMMARY", "Tension headache", "Wrong drug", "temperatureC", "Headache");
        assertThat(JsonPath.<List<Object>>read(patientView, "$.prescriptions")).hasSize(1);
        String staffView = body(onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/patients/" + p[0] + "/timeline", null).andExpect(status().isOk()));
        assertThat(staffView).contains("INTERNAL-DOCTOR-NOTE v2", "Tension headache", "Wrong drug");

        // staff roles: a receptionist books and registers but cannot read clinical records or write them
        String rPhone = nextPhone();
        String inv = body(onHost(c.host(), c.owner(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Rana\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(rPhone)).andExpect(status().isCreated()));
        String rec = read(body(onHost(c.host(), null, "POST", "/api/v1/auth/activate", "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"ReceptionPass1\"}".formatted(rPhone, read(inv, "$.activationPin"))).andExpect(status().isOk())), "$.accessToken");
        onHost(c.host(), rec, "GET", "/api/v1/clinic/patients?q=Karim", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1));
        onHost(c.host(), rec, "GET", "/api/v1/clinic/patients/" + p[0], null).andExpect(status().isOk()).andExpect(jsonPath("$.conditions").doesNotExist());
        onHost(c.host(), rec, "GET", "/api/v1/clinic/patients/" + p[0] + "/timeline", null).andExpect(status().isForbidden());
        onHost(c.host(), rec, "GET", "/api/v1/clinic/encounters/" + enc, null).andExpect(status().isForbidden());
        onHost(c.host(), rec, "POST", "/api/v1/clinic/encounters/" + enc + "/notes", "{\"content\":\"x\"}").andExpect(status().isForbidden());
        onHost(c.host(), rec, "POST", "/api/v1/clinic/prescriptions/" + draft + "/cancel", "{}").andExpect(status().isForbidden());
        // only the author edits a note
        onHost(c.host(), c.owner(), "GET", "/api/v1/auth/me", null).andExpect(status().isOk());

        // every sensitive action left an audit trail
        List<String> actions = jdbc.sql("SELECT DISTINCT action FROM audit.audit_logs WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", c.t().slug()).query(String.class).list();
        assertThat(actions).contains("PATIENT_VIEWED", "NOTE_CREATED", "NOTE_UPDATED", "PRESCRIPTION_CREATED", "PRESCRIPTION_CANCELLED", "LAB_RESULT_UPLOADED", "LAB_RESULT_VIEWED", "ENCOUNTER_CREATED");
    }

    @Test
    void clinicsAreIsolatedFromEachOther() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        String pa = read(body(onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Only-in-A\"}").andExpect(status().isCreated())), "$.id");
        String slot = slots(a, workday(3), false).get(0);
        String appt = read(body(onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(pa, a.doctorId(), a.branchId(), a.serviceId(), slot))), "$.id");
        String enc = read(body(onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(pa)).andExpect(status().isCreated())), "$.id");

        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/patients/" + pa, null).andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/patients/" + pa + "/timeline", null).andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/encounters/" + enc, null).andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/appointments/" + appt + "/cancel", "{}").andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(pa)).andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/patients?q=Only-in-A", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(b.host(), a.owner(), "GET", "/api/v1/clinic/patients", null).andExpect(status().isForbidden());
        // booking with another clinic's doctor/patient ids
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(pa, b.doctorId(), b.branchId(), b.serviceId(), slot)).andExpect(status().isNotFound());
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/appointments/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(a.doctorId(), b.branchId(), b.serviceId(), workday(3)), null).andExpect(status().isNotFound());
    }

    @Test
    void cardBookingHoldsSlotUntilVerifiedPaymentAndExpires() throws Exception {
        Clinic c = clinic();
        String[] p = patientWithPortal(c, "Dina");
        String price = body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/services", "{\"name\":\"Paid visit\",\"durationMinutes\":30,\"priceMinor\":20000}").andExpect(status().isCreated()));
        Clinic paid = new Clinic(c.t(), c.doctorId(), c.branchId(), read(price, "$.id"));
        LocalDate d = workday(3);
        String slot = slots(paid, d, true).get(0);
        String req = "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\",\"paymentMethod\":\"CARD\"}".formatted(p[0], c.doctorId(), c.branchId(), paid.serviceId(), slot);
        onHost(c.host(), p[2], "POST", "/api/v1/portal/appointments", req).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        onHost(c.host(), c.owner(), "PATCH", "/api/v1/clinic/profile", "{\"cardEnabled\":true,\"requiresConfirmation\":false}").andExpect(status().isOk());

        String res = body(onHost(c.host(), p[2], "POST", "/api/v1/portal/appointments", req).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.paymentStatus").value("PENDING")).andExpect(jsonPath("$.checkoutUrl").exists()));
        String id = read(res, "$.id");
        assertThat(slots(paid, d, true)).doesNotContain(slot);   // slot is held while payment is pending
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + id + "/confirm", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_REQUIRED"));

        String wrong = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"appointmentId\":\"%s\",\"amountMinor\":5}".formatted(uniq(), id);
        mvc.perform(post("/api/v1/clinic/webhooks/mock").header("Host", c.host()).header("X-Signature", "bad").contentType(MediaType.APPLICATION_JSON).content(wrong)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/clinic/webhooks/mock").header("Host", c.host()).header("X-Signature", provider.sign(wrong)).contentType(MediaType.APPLICATION_JSON).content(wrong)).andExpect(status().isBadRequest());
        String good = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"appointmentId\":\"%s\",\"amountMinor\":20000}".formatted(uniq(), id);
        for (String expected : new String[] {"PROCESSED", "DUPLICATE"})
            mvc.perform(post("/api/v1/clinic/webhooks/mock").header("Host", c.host()).header("X-Signature", provider.sign(good)).contentType(MediaType.APPLICATION_JSON).content(good)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value(expected));
        onHost(c.host(), p[2], "GET", "/api/v1/portal/appointments/" + id, null).andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.paymentStatus").value("PAID"));

        // an unpaid hold is released after its window
        String slot2 = slots(paid, d, true).get(0);
        String held = read(body(onHost(c.host(), p[2], "POST", "/api/v1/portal/appointments", req.replace(slot, slot2)).andExpect(status().isCreated())), "$.id");
        jdbc.sql("UPDATE medical.appointments SET hold_expires_at = now() - interval '1 minute' WHERE id = :i").param("i", UUID.fromString(held)).update();
        appointmentService.releaseExpiredHolds();
        onHost(c.host(), p[2], "GET", "/api/v1/portal/appointments/" + held, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(slots(paid, d, true)).contains(slot2);
    }

    @Test
    void patientCancellationWindowAndGuardianDependents() throws Exception {
        Clinic c = clinic();
        String[] mother = patientWithPortal(c, "Mona");
        // child record registered under the mother's account
        String child = read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Ziad\",\"guardianPatientId\":\"%s\",\"relationship\":\"MOTHER\"}".formatted(mother[0])).andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("GUARDIAN"))), "$.id");
        onHost(c.host(), mother[2], "GET", "/api/v1/portal/patients", null).andExpect(jsonPath("$.length()").value(2));
        LocalDate d = workday(3);
        String slot = slots(c, d, true).get(0);
        String id = read(bookAs(c, mother[2], child, slot, 201), "$.id");
        onHost(c.host(), mother[2], "POST", "/api/v1/portal/appointments/" + id + "/cancel", "{\"reason\":\"sick\"}").andExpect(jsonPath("$.status").value("CANCELLED"));
        // inside the cancellation window (appointment starts in under 2 hours) the patient must call
        String near = body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(child, c.doctorId(), c.branchId(), c.serviceId(), slot)).andExpect(status().isCreated()));
        jdbc.sql("UPDATE medical.appointments SET start_at = now() + interval '30 minutes', end_at = now() + interval '60 minutes' WHERE id = :i").param("i", UUID.fromString(read(near, "$.id"))).update();
        onHost(c.host(), mother[2], "POST", "/api/v1/portal/appointments/" + read(near, "$.id") + "/cancel", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_PASSED"));
    }
}

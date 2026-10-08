package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

class QueueAndAccessIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    record Clinic(Tenant t, String doctorId, String branchId, String serviceId) {
        String host() { return t.host(); }
        String owner() { return t.access(); }
    }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    Clinic clinic() throws Exception {
        Tenant t = onboard("CLINIC");
        String d = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/doctors", null)), "$[0].id");
        String b = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/branches", null)), "$[0].id");
        String s = JsonPath.<List<String>>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/clinic/services", null)), "$[?(@.name=='Consultation')].id").get(0);
        return new Clinic(t, d, b, s);
    }

    String login(String host, String phone, String password) throws Exception {
        return JsonPath.read(body(onHost(host, null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(phone, password)).andExpect(status().isOk())), "$.accessToken");
    }

    LocalDate workday() {
        LocalDate d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(3);
        while (d.getDayOfWeek().getValue() == 5 || d.getDayOfWeek().getValue() == 6) d = d.plusDays(1);
        return d;
    }

    /** Registers a patient with a clinic-chosen password and returns {patientId, phone}. */
    String[] patientWithPassword(Clinic c, String name, String phone, String password) throws Exception {
        String res = body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/patients",
                "{\"firstName\":\"%s\",\"phone\":\"%s\",\"initialPassword\":\"%s\"}".formatted(name, phone, password)).andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("PASSWORD_SET")));
        assertThat(res).doesNotContain("activationPin");
        return new String[] {JsonPath.read(res, "$.id"), phone};
    }

    @Test
    void clinicSetsPasswordPatientChangesItAndStaffCannotTouchSharedAccounts() throws Exception {
        Clinic a = clinic();
        String phone = nextPhone();
        String[] p = patientWithPassword(a, "Hana", phone, "ClinicSet123");
        // logs in right away, no first-time code
        onHost(a.host(), null, "POST", "/api/v1/auth/check-identifier", "{\"identifier\":\"%s\"}".formatted(phone)).andExpect(jsonPath("$.next").value("ENTER_PASSWORD"));
        String token = login(a.host(), phone, "ClinicSet123");
        onHost(a.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(p[0]));

        // weak password rejected
        onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"X\",\"phone\":\"%s\",\"initialPassword\":\"short\"}".formatted(nextPhone())).andExpect(status().isBadRequest());

        // patient changes the password: wrong current rejected, right one rotates tokens and kills the old refresh token
        String refresh = JsonPath.read(body(onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"ClinicSet123\"}".formatted(phone))), "$.refreshToken");
        onHost(a.host(), token, "POST", "/api/v1/auth/change-password", "{\"currentPassword\":\"nope\",\"newPassword\":\"MyOwnPass456\"}").andExpect(status().isUnauthorized());
        onHost(a.host(), token, "POST", "/api/v1/auth/change-password", "{\"currentPassword\":\"ClinicSet123\",\"newPassword\":\"MyOwnPass456\"}").andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists());
        onHost(a.host(), null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh)).andExpect(status().isUnauthorized());
        onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"ClinicSet123\"}".formatted(phone)).andExpect(status().isUnauthorized());
        login(a.host(), phone, "MyOwnPass456");

        // the clinic may reset it only while the account belongs to this clinic alone
        onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/patients/" + p[0] + "/set-password", "{\"password\":\"FrontDesk789\"}").andExpect(status().isOk());
        login(a.host(), phone, "FrontDesk789");
        Clinic b = clinic();
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Hana\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isCreated());
        onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/patients/" + p[0] + "/set-password", "{\"password\":\"Hijack12345\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_SET_NOT_ALLOWED"));
        login(a.host(), phone, "FrontDesk789");   // unchanged
    }

    @Test
    void onePatientSeesTwoClinicsWithSeparateDataAndCanLeave() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        String phone = nextPhone();
        String[] pa = patientWithPassword(a, "Mariam", phone, "SharedPass123");

        // clinic A records something private to A
        LocalDate d = workday();
        String slot = JsonPath.<List<String>>read(body(onHost(a.host(), a.owner(), "GET", "/api/v1/clinic/appointments/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(a.doctorId(), a.branchId(), a.serviceId(), d), null)), "$").get(0);
        onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(pa[0], a.doctorId(), a.branchId(), a.serviceId(), slot)).andExpect(status().isCreated());
        String enc = JsonPath.read(body(onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\"}".formatted(pa[0]))), "$.id");
        onHost(a.host(), a.owner(), "POST", "/api/v1/clinic/encounters/" + enc + "/notes", "{\"content\":\"ONLY-CLINIC-A-NOTE\",\"patientVisible\":true}").andExpect(status().isCreated());

        // doctor B assigns the already-registered person to his clinic (no code, no password needed)
        String res = body(onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Mariam\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("ASSIGNED")));
        String pb = JsonPath.read(res, "$.id");
        assertThat(res).doesNotContain("activationPin").doesNotContain("linkPin");
        assertThat(pb).isNotEqualTo(pa[0]);
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Mariam\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PATIENT"));
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"M\",\"phone\":\"%s\",\"initialPassword\":\"Overwrite123\"}".formatted(phone)).andExpect(status().isBadRequest());

        // one login, two doctors
        String token = login(a.host(), phone, "SharedPass123");
        onHost("platform.test", token, "GET", "/api/v1/me/tenants", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.host=='%s')].type".formatted(a.host())).value("CLINIC")).andExpect(jsonPath("$[?(@.host=='%s')].name".formatted(b.host())).exists());
        // the same token opens each clinic, each showing only its own data
        onHost(a.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(jsonPath("$[0].id").value(pa[0]));
        onHost(b.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(jsonPath("$[0].id").value(pb));
        String tlA = body(onHost(a.host(), token, "GET", "/api/v1/portal/patients/" + pa[0] + "/timeline", null).andExpect(status().isOk()));
        String tlB = body(onHost(b.host(), token, "GET", "/api/v1/portal/patients/" + pb + "/timeline", null).andExpect(status().isOk()));
        assertThat(tlA).contains("ONLY-CLINIC-A-NOTE");
        assertThat(tlB).doesNotContain("ONLY-CLINIC-A-NOTE");
        assertThat(JsonPath.<List<Object>>read(tlB, "$.appointments")).isEmpty();
        onHost(b.host(), token, "GET", "/api/v1/portal/patients/" + pa[0] + "/timeline", null).andExpect(status().isNotFound());   // A's record id is meaningless at B
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/patients/" + pa[0], null).andExpect(status().isNotFound());               // and doctor B cannot read it
        onHost(a.host(), a.owner(), "GET", "/api/v1/clinic/patients/" + pb, null).andExpect(status().isNotFound());
        // a clinic assignment is announced to the patient
        assertThat(JsonPath.<List<String>>read(body(onHost(b.host(), token, "GET", "/api/v1/notifications", null)), "$.data[*].notificationType")).contains("CLINIC_ADDED");

        // the patient leaves clinic B: it disappears from the app, its record stays with the clinic, and clinic A is untouched
        onHost(b.host(), token, "POST", "/api/v1/portal/leave", "{}").andExpect(status().isOk());
        onHost("platform.test", token, "GET", "/api/v1/me/tenants", null).andExpect(jsonPath("$.length()").value(1));
        onHost(b.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(status().isForbidden());
        onHost(b.host(), b.owner(), "GET", "/api/v1/clinic/patients/" + pb, null).andExpect(status().isOk());
        onHost(a.host(), token, "GET", "/api/v1/portal/patients", null).andExpect(status().isOk());
        // adding the person again reconnects the SAME record (no duplicate file)
        onHost(b.host(), b.owner(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Mariam\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(pb));
        onHost("platform.test", token, "GET", "/api/v1/me/tenants", null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void queueOrdersPatientsCallsThemAndPatientsSeeOnlyTheirOwnPlace() throws Exception {
        Clinic c = clinic();
        String[] p1 = patientWithPassword(c, "First", nextPhone(), "QueuePass123");
        String[] p2 = patientWithPassword(c, "Second", nextPhone(), "QueuePass123");
        String t1 = login(c.host(), p1[1], "QueuePass123");
        String t2 = login(c.host(), p2[1], "QueuePass123");
        LocalDate d = workday();
        List<String> slots = JsonPath.read(body(onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/appointments/slots?doctorId=%s&branchId=%s&serviceId=%s&date=%s".formatted(c.doctorId(), c.branchId(), c.serviceId(), d), null)), "$");
        String a1 = JsonPath.read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(p1[0], c.doctorId(), c.branchId(), c.serviceId(), slots.get(0)))), "$.id");
        String a2 = JsonPath.read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments", "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"branchId\":\"%s\",\"serviceId\":\"%s\",\"startAt\":\"%s\"}".formatted(p2[0], c.doctorId(), c.branchId(), c.serviceId(), slots.get(1)))), "$.id");
        // move both to today so they belong in today's queue (the appointment date is what places them on the day's screen)
        jdbc.sql("UPDATE medical.appointments SET start_at = now() + interval '1 hour', end_at = now() + interval '90 minutes' WHERE id = :i").param("i", UUID.fromString(a1)).update();
        jdbc.sql("UPDATE medical.appointments SET start_at = now() + interval '2 hours', end_at = now() + interval '150 minutes' WHERE id = :i").param("i", UUID.fromString(a2)).update();

        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue", null).andExpect(jsonPath("$.waiting.length()").value(0));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a1 + "/call", "{}").andExpect(status().isConflict());   // not checked in yet

        // the second patient arrives first and is therefore served first
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a2 + "/check-in", "{}").andExpect(jsonPath("$.queueNumber").value(1));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a1 + "/check-in", "{}").andExpect(jsonPath("$.queueNumber").value(2));
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue", null).andExpect(jsonPath("$.waiting.length()").value(2))
                .andExpect(jsonPath("$.waiting[0].patientName").value("Second"))   // first and last name joined and trimmed
                .andExpect(jsonPath("$.waiting[1].patientName").value("First"))
                .andExpect(jsonPath("$.waiting[0].queueNumber").value(1));

        // each patient sees only their own place: counts, never names
        String mine1 = body(onHost(c.host(), t1, "GET", "/api/v1/portal/queue", null).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(mine1, "$[0].aheadOfYou")).isEqualTo(1);
        assertThat(mine1).doesNotContain("Second");
        assertThat(JsonPath.<Integer>read(body(onHost(c.host(), t2, "GET", "/api/v1/portal/queue", null)), "$[0].aheadOfYou")).isZero();

        // doctor calls patient 2: they are notified (generic text) and flagged as called
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a2 + "/call", "{}").andExpect(status().isOk());
        onHost(c.host(), t2, "GET", "/api/v1/portal/queue", null).andExpect(jsonPath("$[0].called").value(true));
        String inbox = body(onHost(c.host(), t2, "GET", "/api/v1/notifications", null));
        assertThat(JsonPath.<List<String>>read(inbox, "$.data[*].notificationType")).contains("APPOINTMENT_CALLED");

        // visit starts: patient 2 leaves the waiting list and shows as being seen; patient 1 is now first
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a2 + "/start", "{}").andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue?doctorId=" + c.doctorId(), null).andExpect(jsonPath("$.inProgress.length()").value(1)).andExpect(jsonPath("$.waiting.length()").value(1));
        onHost(c.host(), t1, "GET", "/api/v1/portal/queue", null).andExpect(jsonPath("$[0].aheadOfYou").value(0)).andExpect(jsonPath("$[0].doctorBusy").value(true));
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/" + a1 + "/no-show", "{}").andExpect(jsonPath("$.status").value("NO_SHOW"));
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue", null).andExpect(jsonPath("$.waiting.length()").value(0));

        // patients cannot read the staff queue; another clinic sees none of it
        onHost(c.host(), t1, "GET", "/api/v1/clinic/queue", null).andExpect(status().isForbidden());
        Clinic other = clinic();
        onHost(other.host(), other.owner(), "GET", "/api/v1/clinic/queue", null).andExpect(jsonPath("$.inProgress.length()").value(0));
    }

    @Test
    void walkInJoinsTheQueueWheneverThePatientIsAtTheDesk() throws Exception {
        Clinic c = clinic();
        String[] a = patientWithPassword(c, "Walkin", nextPhone(), "WalkPass1234");
        String[] b = patientWithPassword(c, "Second", nextPhone(), "WalkPass1234");
        String walk = "{\"patientId\":\"%s\",\"doctorId\":\"" + c.doctorId() + "\",\"branchId\":\"" + c.branchId() + "\",\"serviceId\":\"" + c.serviceId() + "\"}";
        // works at any hour and on any day (including the weekend or after clinic hours): the schedule does not decide for someone already there
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(a[0])).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CHECKED_IN")).andExpect(jsonPath("$.queueNumber").value(1));
        // a second walk-in right after gets the next number and a start that does not overlap the first
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(b[0])).andExpect(status().isCreated()).andExpect(jsonPath("$.queueNumber").value(2));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM medical.appointments x JOIN medical.appointments y ON x.doctor_id = y.doctor_id AND x.id < y.id
                WHERE x.doctor_id = :d AND x.status = 'CHECKED_IN' AND y.status = 'CHECKED_IN' AND tstzrange(x.start_at, x.end_at, '[)') && tstzrange(y.start_at, y.end_at, '[)')
                """).param("d", UUID.fromString(c.doctorId())).query(Long.class).single()).isZero();
        onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue", null).andExpect(jsonPath("$.waiting.length()").value(2));
        // the same patient twice is still just a second visit entry, never an error about "no free time"
        onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(a[0])).andExpect(status().isCreated());
    }

    @Test
    void bookingPageKnowsWhichWeekdaysTheDoctorWorks() throws Exception {
        Clinic c = clinic();
        // default hours: Sunday-Thursday (ISO 7,1,2,3,4); Friday and Saturday are days off
        String res = body(onHost(c.host(), null, "GET", "/api/v1/clinic/public/working-days?doctorId=" + c.doctorId() + "&branchId=" + c.branchId(), null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(res, "$.weekdays")).containsExactly(1, 2, 3, 4, 7);
        LocalDate friday = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(3);
        while (friday.getDayOfWeek().getValue() != 5) friday = friday.plusDays(1);
        String slots = body(onHost(c.host(), null, "GET", "/api/v1/clinic/public/slots?doctorId=" + c.doctorId() + "&branchId=" + c.branchId() + "&serviceId=" + c.serviceId() + "&date=" + friday, null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(slots, "$")).isEmpty();
        // another clinic's doctor id reveals nothing
        Clinic other = clinic();
        assertThat(JsonPath.<List<Integer>>read(body(onHost(other.host(), null, "GET", "/api/v1/clinic/public/working-days?doctorId=" + c.doctorId() + "&branchId=" + other.branchId(), null)), "$.weekdays")).isEmpty();
    }

    @Test
    void secretarySendsInAndDoctorOpensTheCurrentExamOnce() throws Exception {
        Clinic c = clinic();
        String rPhone = nextPhone();
        String inv = body(onHost(c.host(), c.owner(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Rana\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(rPhone)).andExpect(status().isCreated()));
        String sec = JsonPath.read(body(onHost(c.host(), null, "POST", "/api/v1/auth/activate", "{\"identifier\":\"%s\",\"pin\":\"%s\",\"newPassword\":\"ReceptionPass1\"}".formatted(rPhone, JsonPath.<String>read(inv, "$.activationPin"))).andExpect(status().isOk())), "$.accessToken");
        String[] p = patientWithPassword(c, "Current", nextPhone(), "WalkPass1234");
        String walk = "{\"patientId\":\"%s\",\"doctorId\":\"" + c.doctorId() + "\",\"branchId\":\"" + c.branchId() + "\",\"serviceId\":\"" + c.serviceId() + "\"}";

        // the secretary registers the arrival, then sends the patient in; she cannot open the clinical exam herself
        String appt = JsonPath.read(body(onHost(c.host(), sec, "POST", "/api/v1/clinic/appointments/walk-in", walk.formatted(p[0])).andExpect(status().isCreated())), "$.id");
        onHost(c.host(), sec, "POST", "/api/v1/clinic/appointments/" + appt + "/start", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        onHost(c.host(), sec, "POST", "/api/v1/clinic/encounters", "{\"patientId\":\"%s\",\"appointmentId\":\"%s\"}".formatted(p[0], appt)).andExpect(status().isForbidden());

        // the doctor's current-exam screen reads the queue: the patient is in the room with age and mobile
        String q = body(onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/queue?doctorId=" + c.doctorId(), null).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(q, "$.inProgress[0].patientName")).contains("Current");
        assertThat(JsonPath.<String>read(q, "$.inProgress[0].patientPhone")).startsWith("+20");
        assertThat(JsonPath.<String>read(q, "$.inProgress[0].id")).isEqualTo(appt);

        // the doctor opens the exam; opening it again (second click, reload, second tab) continues the SAME exam
        String body = "{\"patientId\":\"%s\",\"appointmentId\":\"%s\"}".formatted(p[0], appt);
        String e1 = JsonPath.read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters", body).andExpect(status().isCreated())), "$.id");
        String e2 = JsonPath.read(body(onHost(c.host(), c.owner(), "POST", "/api/v1/clinic/encounters", body).andExpect(status().isCreated())), "$.id");
        assertThat(e2).isEqualTo(e1);
        assertThat(jdbc.sql("SELECT count(*) FROM medical.encounters WHERE appointment_id = :a").param("a", UUID.fromString(appt)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void staffCanEditPatientDetailsButNotBlankTheNameOrCrossClinics() throws Exception {
        Clinic c = clinic();
        String[] p = patientWithPassword(c, "Editme", nextPhone(), "WalkPass1234");
        String res = body(onHost(c.host(), c.owner(), "PATCH", "/api/v1/clinic/patients/" + p[0],
                "{\"firstName\":\"Renamed Patient\",\"lastName\":\"\",\"phone\":\"٠١٠٥٥٥٠٠٠٠٩\",\"ageYears\":7,\"ageMonths\":3,\"sex\":\"M\",\"addressText\":\"12 Nile St\",\"bloodType\":\"O+\",\"notesInternal\":\"allergic to penicillin\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(res, "$.firstName")).isEqualTo("Renamed Patient");
        assertThat(JsonPath.<String>read(res, "$.lastName")).isEmpty();
        assertThat(JsonPath.<Integer>read(res, "$.ageYears")).isEqualTo(7);
        assertThat(JsonPath.<Integer>read(res, "$.ageMonths")).isEqualTo(3);
        assertThat(JsonPath.<String>read(res, "$.addressText")).isEqualTo("12 Nile St");
        assertThat(JsonPath.<String>read(res, "$.bloodType")).isEqualTo("O+");
        assertThat(JsonPath.<String>read(res, "$.phone")).isEqualTo("+201055500009");
        assertThat(body(onHost(c.host(), c.owner(), "GET", "/api/v1/clinic/patients/" + p[0], null))).contains("allergic to penicillin");
        // a blank name is refused, an unknown / foreign id is a 404, and the change is audited
        onHost(c.host(), c.owner(), "PATCH", "/api/v1/clinic/patients/" + p[0], "{\"firstName\":\"  \"}").andExpect(status().isBadRequest());
        Clinic other = clinic();
        onHost(other.host(), other.owner(), "PATCH", "/api/v1/clinic/patients/" + p[0], "{\"firstName\":\"Hacked\"}").andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'PATIENT_UPDATED' AND entity_id = :p").param("p", UUID.fromString(p[0])).query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }
}

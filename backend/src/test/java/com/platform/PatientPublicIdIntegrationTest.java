package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The platform-wide patient number: shown to the patient, usable to sign in, and used by a clinic to add an existing patient. */
class PatientPublicIdIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void numberIsAssignedLoginWorksAndAnotherClinicCanAddThePatientByNumberOrPhone() throws Exception {
        Tenant a = onboard("CLINIC"), b = onboard("CLINIC");
        String phone = nextPhone();
        String created = body(onHost(a.host(), a.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Salma\",\"phone\":\"%s\",\"initialPassword\":\"ClinicSet123\"}".formatted(phone)).andExpect(status().isCreated()));
        // the staff view shows the patient's platform number (6 digits)
        String patientId = JsonPath.read(created, "$.id");
        int number = JsonPath.<Integer>read(body(onHost(a.host(), a.access(), "GET", "/api/v1/clinic/patients/" + patientId, null).andExpect(status().isOk())), "$.publicId");
        assertThat(number).isBetween(100000, 999999);

        // signing in works with the number as well as the phone
        onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%d\",\"password\":\"ClinicSet123\"}".formatted(number)).andExpect(status().isOk());
        onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"ClinicSet123\"}".formatted(phone)).andExpect(status().isOk());
        onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%d\",\"password\":\"wrong-password\"}".formatted(number)).andExpect(status().isUnauthorized());
        // the platform host answers /me/patient with the number
        String token = JsonPath.read(body(onHost(a.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%d\",\"password\":\"ClinicSet123\"}".formatted(number))), "$.accessToken");
        onHost(a.host(), token, "GET", "/api/v1/me/patient", null).andExpect(status().isOk()).andExpect(jsonPath("$.publicId").value(number));

        // another clinic looks the patient up: only the name comes back, they are not its patient yet
        onHost(b.host(), b.access(), "GET", "/api/v1/clinic/patients/lookup?q=" + number, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true)).andExpect(jsonPath("$.displayName").value("Salma")).andExpect(jsonPath("$.alreadyInClinic").value(false)).andExpect(jsonPath("$.phone").doesNotExist());
        onHost(b.host(), b.access(), "GET", "/api/v1/clinic/patients/lookup?q=999999999", null).andExpect(jsonPath("$.found").value(false));
        onHost(b.host(), b.access(), "POST", "/api/v1/clinic/patients/link", "{\"q\":\"999999999\"}").andExpect(status().isNotFound());

        // adding by number creates this clinic's own record (own patient code, own id); the other clinic's record is untouched
        String linked = body(onHost(b.host(), b.access(), "POST", "/api/v1/clinic/patients/link", "{\"q\":\"%d\"}".formatted(number)).andExpect(status().isCreated()).andExpect(jsonPath("$.portalAccess").value("ASSIGNED")));
        assertThat(JsonPath.<String>read(linked, "$.id")).isNotEqualTo(patientId);
        assertThat(JsonPath.<Integer>read(linked, "$.publicId")).isEqualTo(number);
        onHost(b.host(), b.access(), "POST", "/api/v1/clinic/patients/link", "{\"q\":\"%d\"}".formatted(number)).andExpect(status().isConflict());
        onHost(b.host(), b.access(), "GET", "/api/v1/clinic/patients/lookup?q=" + phone, null).andExpect(jsonPath("$.alreadyInClinic").value(true));
        // the clinic's patient list finds the patient by number
        assertThat(JsonPath.<Integer>read(body(onHost(b.host(), b.access(), "GET", "/api/v1/clinic/patients?q=" + number, null)), "$.meta.total")).isEqualTo(1);
        // adding by phone works too (a third clinic)
        Tenant c = onboard("CLINIC");
        onHost(c.host(), c.access(), "POST", "/api/v1/clinic/patients/link", "{\"q\":\"%s\"}".formatted(phone)).andExpect(status().isCreated());
    }

    @Test
    void everyAccountGetsADifferentSixDigitNumber() throws Exception {
        Tenant a = onboard("CLINIC");
        for (int i = 0; i < 5; i++) onHost(a.host(), a.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"P%d\",\"phone\":\"%s\",\"initialPassword\":\"ClinicSet123\"}".formatted(i, nextPhone())).andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT count(*) FROM medical.patient_accounts WHERE public_id IS NULL OR public_id < 100000").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) - count(DISTINCT public_id) FROM medical.patient_accounts").query(Long.class).single()).isZero();
    }
}

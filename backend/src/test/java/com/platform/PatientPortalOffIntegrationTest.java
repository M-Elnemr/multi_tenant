package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Platform default: patient accounts are off. The clinic keeps patients as plain records (mobile required, no email), entered by age. */
@TestPropertySource(properties = "app.patient-portal.enabled=false")
class PatientPortalOffIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void patientsAreRecordsOnlyWithMobileAndAge() throws Exception {
        Tenant c = onboard("CLINIC");
        String h = c.host();
        onHost(h, c.access(), "GET", "/api/v1/clinic/profile", null).andExpect(jsonPath("$.patientPortalEnabled").value(false));
        onHost(h, null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.patientPortalEnabled").value(false));

        // mobile is required
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"NoPhone\",\"ageYears\":5}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // account options and email are ignored: no user account, no PIN, no email kept
        String phone = nextPhone();
        String res = body(onHost(h, c.access(), "POST", "/api/v1/clinic/patients",
                "{\"firstName\":\"Mona\",\"phone\":\"%s\",\"email\":\"mona@example.com\",\"initialPassword\":\"ClinicSet123\",\"ageYears\":2,\"ageMonths\":6,\"sex\":\"F\"}".formatted(phone)).andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(res, "$.portalAccess")).isEqualTo("NONE");
        assertThat(res).doesNotContain("activationPin").doesNotContain("mona@example.com").doesNotContain("\"email\"");
        assertThat(jdbc.sql("SELECT count(*) FROM core.users WHERE phone = :p").param("p", phone).query(Long.class).single()).isZero();
        String id = JsonPath.read(res, "$.id");

        // age entered as years + months is stored as an estimated birth date and read back as the same age
        assertThat((Integer) JsonPath.read(res, "$.ageYears")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(res, "$.ageMonths")).isEqualTo(6);
        assertThat((Boolean) JsonPath.read(res, "$.dobEstimated")).isTrue();
        LocalDate dob = LocalDate.parse(JsonPath.read(res, "$.dateOfBirth"));
        assertThat(dob).isBetween(LocalDate.now().minusYears(2).minusMonths(6).minusDays(2), LocalDate.now().minusYears(2).minusMonths(6).plusDays(2));
        String list = body(onHost(h, c.access(), "GET", "/api/v1/clinic/patients?q=Mona", null).andExpect(status().isOk()));
        assertThat((Integer) JsonPath.read(list, "$.data[0].ageYears")).isEqualTo(2);

        // bounds: months 0-11, years 0-130
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"X\",\"phone\":\"%s\",\"ageYears\":1,\"ageMonths\":12}".formatted(nextPhone())).andExpect(status().isBadRequest());
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"X\",\"phone\":\"%s\",\"ageYears\":131}".formatted(nextPhone())).andExpect(status().isBadRequest());
        // baby under one year
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients", "{\"firstName\":\"Baby\",\"phone\":\"%s\",\"ageYears\":0,\"ageMonths\":4}".formatted(nextPhone())).andExpect(status().isCreated()).andExpect(jsonPath("$.ageYears").value(0)).andExpect(jsonPath("$.ageMonths").value(4));
        // editing the age updates it
        onHost(h, c.access(), "PATCH", "/api/v1/clinic/patients/" + id, "{\"ageYears\":3,\"ageMonths\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.ageYears").value(3)).andExpect(jsonPath("$.ageMonths").value(0));

        // the same mobile can be a patient of another clinic; patient-account endpoints do not exist while off
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients/" + id + "/set-password", "{\"password\":\"Another12345\"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PORTAL_DISABLED"));
        onHost(h, c.access(), "POST", "/api/v1/clinic/patients/" + id + "/access-pin", "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PORTAL_DISABLED"));
        onHost(h, c.access(), "GET", "/api/v1/portal/patients", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PORTAL_DISABLED"));
        onHost(h, null, "POST", "/api/v1/clinic/portal/link", "{\"identifier\":\"x\",\"password\":\"y\",\"patientCode\":\"z\",\"pin\":\"1\"}").andExpect(status().isNotFound());
    }
}

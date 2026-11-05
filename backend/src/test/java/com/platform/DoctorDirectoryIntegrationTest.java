package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/** The patient app's doctor directory: public, lists doctors of live clinics (never shops), filters by name, fee and specialty, and clinics pick a website design. */
class DoctorDirectoryIntegrationTest extends IntegrationTestBase {
    @Test
    void doctorsOfLiveClinicsAreListedAndFilterable() throws Exception {
        Tenant clinic = onboard("CLINIC");
        onboard("STORE");
        String doctors = onHost(clinic.host(), clinic.access(), "GET", "/api/v1/clinic/doctors", null).andReturn().getResponse().getContentAsString();
        String doctorId = JsonPath.read(doctors, "$[0].id");
        String name = "Dr Directory " + clinic.slug();
        onHost(clinic.host(), clinic.access(), "PATCH", "/api/v1/clinic/doctors/" + doctorId, "{\"fields\":{\"displayName\":\"%s\",\"yearsExperience\":9,\"defaultAppointmentFeeMinor\":45000}}".formatted(name)).andExpect(status().isOk());

        String found = onHost("platform.test", null, "GET", "/api/v1/tenant/doctors?q=" + clinic.slug(), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(found, "$.data.length()")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<String>read(found, "$.data[0].clinic.slug")).isEqualTo(clinic.slug());
        assertThat(JsonPath.<String>read(found, "$.data[0].clinic.baseUrl")).startsWith("https://");
        // fee range filter
        assertThat(onHost("platform.test", null, "GET", "/api/v1/tenant/doctors?q=" + clinic.slug() + "&maxFee=10000", null).andReturn().getResponse().getContentAsString()).contains("\"data\":[]");
        assertThat(onHost("platform.test", null, "GET", "/api/v1/tenant/doctors?q=" + clinic.slug() + "&minFee=40000&maxFee=50000", null).andReturn().getResponse().getContentAsString()).contains(clinic.slug());
        // an unknown specialty matches nobody; unknown sort falls back
        assertThat(onHost("platform.test", null, "GET", "/api/v1/tenant/doctors?q=" + clinic.slug() + "&specialty=zzz", null).andReturn().getResponse().getContentAsString()).contains("\"data\":[]");
        onHost("platform.test", null, "GET", "/api/v1/tenant/doctors?sort=bogus", null).andExpect(status().isOk());
        onHost("platform.test", null, "GET", "/api/v1/tenant/doctors/filters", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.specialties").isArray()).andExpect(jsonPath("$.cities").isArray()).andExpect(jsonPath("$.onlineBooking").isBoolean());
    }

    @Test
    void clinicPicksAWebsiteDesign() throws Exception {
        Tenant c = onboard("CLINIC");
        onHost(c.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.template").value("clear"));
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/profile", "{\"template\":\"luxe\"}").andExpect(status().isOk());
        onHost(c.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(jsonPath("$.template").value("luxe"));
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/profile", "{\"template\":\"neon\"}").andExpect(status().isBadRequest());
    }

    @Test
    void shopCanPickTheNewDesigns() throws Exception {
        Tenant s = onboard("STORE");
        for (String tpl : new String[]{"street", "grove", "maison", "feast"})
            onHost(s.host(), s.access(), "PATCH", "/api/v1/store/profile", "{\"template\":\"%s\"}".formatted(tpl)).andExpect(status().isOk());
        onHost(s.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.profile.template").value("feast"));
    }
}

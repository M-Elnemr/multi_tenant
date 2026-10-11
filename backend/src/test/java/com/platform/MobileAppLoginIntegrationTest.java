package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** The two mobile apps sign in on the platform address with no domain: each accepts only its own kind of account and says so. */
class MobileAppLoginIntegrationTest extends IntegrationTestBase {
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void invitedColleagueIsToldToUseThePinNotWrongPassword() throws Exception {
        Tenant clinic = onboard("CLINIC");
        String phone = nextPhone();
        onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Rana\",\"phone\":\"%s\",\"role\":\"RECEPTIONIST\"}".formatted(phone)).andExpect(status().isCreated());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"Whatever123\"}".formatted(phone)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVATION_REQUIRED"));
        // an unknown number is still just wrong credentials
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"Whatever123\"}".formatted(nextPhone())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void eachAppRefusesTheOtherKindOfAccount() throws Exception {
        Tenant clinic = onboard("CLINIC");
        PatientLogin p = patientAt(clinic, "Mona");
        // the business app does not take a patient...
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\",\"app\":\"business\"}".formatted(p.phone())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_APP_PATIENT"));
        // ...the client app does not take a business owner...
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\",\"app\":\"client\"}".formatted(clinic.phone())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_APP_STAFF"));
        // ...and each accepts its own
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\",\"app\":\"client\"}".formatted(p.phone())).andExpect(status().isOk());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\",\"app\":\"business\"}".formatted(clinic.phone())).andExpect(status().isOk());
    }

    @Test
    void businessOwnerSeesTheirBusinessesAndShopsAreListedPubliclyWithFilters() throws Exception {
        Tenant shop = onboard("STORE");
        Tenant clinic = onboard("CLINIC");
        String access = JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\",\"app\":\"business\"}".formatted(shop.phone())).andExpect(status().isOk())), "$.accessToken");
        String mine = body(onHost("platform.test", access, "GET", "/api/v1/me/businesses", null).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(mine, "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(mine, "$[0].type")).isEqualTo("STORE");
        assertThat(JsonPath.<String>read(mine, "$[0].baseUrl")).startsWith("https://").contains(shop.slug());
        assertThat(JsonPath.<java.util.List<String>>read(mine, "$[0].roles")).contains("STORE_OWNER");

        // the public directory lists shops (never clinics), searchable by name/slug and filterable
        String list = body(onHost("platform.test", null, "GET", "/api/v1/tenant/stores?q=" + shop.slug(), null).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(list, "$.data.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(list, "$.data[0].slug")).isEqualTo(shop.slug());
        assertThat(body(onHost("platform.test", null, "GET", "/api/v1/tenant/stores?q=" + clinic.slug(), null))).contains("\"data\":[]");
        assertThat(body(onHost("platform.test", null, "GET", "/api/v1/tenant/stores?governorate=ZZZ", null))).doesNotContain(shop.slug());
        onHost("platform.test", null, "GET", "/api/v1/tenant/stores/filters", null).andExpect(status().isOk()).andExpect(jsonPath("$.categories").isArray()).andExpect(jsonPath("$.governorates").isArray());
    }
}

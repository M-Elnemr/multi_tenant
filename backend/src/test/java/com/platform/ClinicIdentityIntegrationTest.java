package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/** The public clinic website shows contact channels, hours, FAQs, insurance, doctors' details and branch details the owner enters. */
class ClinicIdentityIntegrationTest extends IntegrationTestBase {
    @Test
    void ownerFillsTheWebsiteDetailsAndVisitorsSeeThem() throws Exception {
        Tenant c = onboard("CLINIC");
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/profile", """
                {"tagline":"Care you can trust","whatsapp":"01001234567","facebookUrl":"https://facebook.com/x","instagramUrl":"https://instagram.com/x",
                 "mapsUrl":"https://maps.google.com/?q=1","announcement":"Closed on Friday","isOpen":true,"establishedYear":2015,
                 "workingHours":{"sat":[{"from":"09:00","to":"17:00"}]},"insurance":["Axa","Bupa"],"faqs":[{"q":"Do I need to book?","a":"Walk-ins are welcome."},{"q":"","a":"skipped"}],
                 "extraPhones":["0223334444"]}
                """).andExpect(status().isOk());
        onHost(c.host(), null, "GET", "/api/v1/clinic/public/profile", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").value("Care you can trust")).andExpect(jsonPath("$.whatsapp").value("01001234567"))
                .andExpect(jsonPath("$.insurance.length()").value(2)).andExpect(jsonPath("$.faqs.length()").value(1)).andExpect(jsonPath("$.faqs[0].a").value("Walk-ins are welcome."))
                .andExpect(jsonPath("$.workingHours.sat[0].from").value("09:00")).andExpect(jsonPath("$.establishedYear").value(2015)).andExpect(jsonPath("$.extraPhones[0]").value("0223334444"));
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/profile", "{\"facebookUrl\":\"javascript:alert(1)\"}").andExpect(status().isBadRequest());

        // branches and doctors can be edited by the owner
        String branches = onHost(c.host(), c.access(), "GET", "/api/v1/clinic/branches", null).andReturn().getResponse().getContentAsString();
        String branchId = JsonPath.read(branches, "$[0].id");
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/branches/" + branchId, "{\"landmark\":\"Next to the metro\",\"mapsUrl\":\"https://maps.google.com/?q=2\",\"whatsapp\":\"01011112222\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.landmark").value("Next to the metro"));
        String doctors = onHost(c.host(), c.access(), "GET", "/api/v1/clinic/doctors", null).andReturn().getResponse().getContentAsString();
        String doctorId = JsonPath.read(doctors, "$[0].id");
        onHost(c.host(), c.access(), "PATCH", "/api/v1/clinic/doctors/" + doctorId, "{\"fields\":{\"yearsExperience\":12,\"qualifications\":\"MD Cairo University\",\"languages\":[\"Arabic\",\"English\"],\"bio\":\"Consultant\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.yearsExperience").value(12)).andExpect(jsonPath("$.languages.length()").value(2));
        onHost(c.host(), null, "GET", "/api/v1/clinic/public/profile", null)
                .andExpect(jsonPath("$.branches[0].landmark").value("Next to the metro")).andExpect(jsonPath("$.doctors[0].qualifications").value("MD Cairo University"));

        // the public site shows nothing private to patients: only the owner changes things
        onHost(c.host(), null, "PATCH", "/api/v1/clinic/doctors/" + doctorId, "{\"fields\":{\"bio\":\"hack\"}}").andExpect(status().is4xxClientError());
    }
}

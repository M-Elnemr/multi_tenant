package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A law office is its own kind of business: nothing about a doctor's clinic applies to it (roles, contacts, labels). */
class LawyerIndependenceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    Tenant lawyer() throws Exception {
        String slug = "law" + uniq(), phone = nextPhone();
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"LAWYER","name":"Ahmed Law","slug":"%s","ownerFirstName":"Ahmed","phone":"%s","password":"s3cretPass!","categories":["criminal","family"]}
                        """.formatted(slug, phone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone, JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    List<String> rolesOf(String phone, String slug) {
        return jdbc.sql("""
                SELECT r.code FROM core.user_tenant_memberships m JOIN core.users u ON u.id = m.user_id JOIN core.tenants t ON t.id = m.tenant_id
                JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id WHERE t.slug = :s AND u.phone LIKE :p
                """).param("s", slug).param("p", "%" + phone.substring(phone.length() - 9)).query(String.class).list();
    }

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void anEmployeeOfALawOfficeWithChosenScreensIsALawOfficeEmployeeNeverAClinicOne() throws Exception {
        Tenant law = lawyer();
        String phone = nextPhone();
        onHost(law.host(), law.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Mona\",\"phone\":\"%s\",\"access\":{\"clients\":\"READ\",\"bookings\":\"WRITE\"}}".formatted(phone)).andExpect(status().isCreated());
        List<String> roles = rolesOf(phone, law.slug());
        assertThat(roles).containsExactly("LAWYER_STAFF");
        assertThat(roles).doesNotContain("CLINIC_STAFF", "STORE_STAFF");
        // a clinic keeps its own, a shop too
        Tenant clinic = onboard("CLINIC");
        String p2 = nextPhone();
        onHost(clinic.host(), clinic.access(), "POST", "/api/v1/tenant/members", "{\"firstName\":\"Sara\",\"phone\":\"%s\",\"access\":{\"patients\":\"READ\"}}".formatted(p2)).andExpect(status().isCreated());
        assertThat(rolesOf(p2, clinic.slug())).containsExactly("CLINIC_STAFF");
    }

    @Test
    void thePlatformSeesALawOfficesOwnerContactAndItsTypeNotAClinic() throws Exception {
        Tenant law = lawyer();
        String admin = platformToken();
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", law.slug()).query(UUID.class).single();
        onHost("platform.test", admin, "GET", "/api/v1/platform/tenants/" + id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.phone").isNotEmpty()).andExpect(jsonPath("$.tenantType").value("LAWYER"));
    }
}

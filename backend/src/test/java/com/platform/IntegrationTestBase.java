package com.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.core.auth.LoginThrottle;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Shared helpers: onboard a tenant, call the API as a given host/token. Every module's tests build on this. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    @Autowired protected MockMvc mvc;
    @Autowired protected LoginThrottle throttle;

    @BeforeEach
    void resetThrottle() { throttle.clearAll(); }

    // ---- helpers ---------------------------------------------------------------------------

    public record Tenant(String slug, String host, String phone, String access, String refresh) {}

    protected static final java.util.concurrent.atomic.AtomicLong PHONE = new java.util.concurrent.atomic.AtomicLong(10_000_000L + (System.currentTimeMillis() % 80_000_000L));
    protected static String nextPhone() { return "+2010" + PHONE.incrementAndGet(); }

    protected static String uniq() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

    protected Tenant onboard(String type) throws Exception {
        String slug = (type.equals("STORE") ? "shop" : "clinic") + uniq();
        String phone = nextPhone();
        String body = """
                {"type":"%s","name":"Test %s","slug":"%s","ownerFirstName":"Owner","phone":"%s","password":"s3cretPass!","categories":["%s"]}
                """.formatted(type, slug, slug, phone, type.equals("STORE") ? "fashion_men" : "general_practice");
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.status").value("TRIAL"))
                .andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone,
                JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    protected ResultActions onHost(String host, String token, String method, String path, String body) throws Exception {
        return onHost(host, token, method, path, body, null);
    }

    protected ResultActions onHost(String host, String token, String method, String path, String body, String idempotencyKey) throws Exception {
        var b = switch (method) {
            case "POST" -> post(path);
            case "PATCH" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path);
            case "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        b.header("Host", host);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }


    /** A shop client signs in with Google on a store's host (the test fake accepts "test|sub|email|name"). Returns the access token. */
    protected String googleClient(String storeHost) throws Exception { return googleClient(storeHost, "g" + uniq(), "Mona Client"); }

    protected String googleClient(String storeHost, String sub, String name) throws Exception {
        String res = mvc.perform(post("/api/v1/auth/client/google").header("Host", storeHost).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"test|%s|%s@example.com|%s\"}".formatted(sub, sub, name)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.accessToken");
    }

    public record PatientLogin(String patientId, String code, String token, String phone) {}

    /** The clinic registers a patient with a temporary password; the patient signs in and chooses their own password. */
    protected PatientLogin patientAt(Tenant clinic, String firstName) throws Exception { return patientAt(clinic, firstName, nextPhone()); }

    protected PatientLogin patientAt(Tenant clinic, String firstName, String phone) throws Exception {
        String res = onHost(clinic.host(), clinic.access(), "POST", "/api/v1/clinic/patients",
                "{\"firstName\":\"%s\",\"phone\":\"%s\",\"initialPassword\":\"TempPass123\"}".formatted(firstName, phone)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String login = onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"TempPass123\"}".formatted(phone)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true)).andReturn().getResponse().getContentAsString();
        String temp = JsonPath.read(login, "$.accessToken");
        String changed = onHost(clinic.host(), temp, "POST", "/api/v1/auth/change-password", "{\"currentPassword\":\"TempPass123\",\"newPassword\":\"PatientPass1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(false)).andReturn().getResponse().getContentAsString();
        return new PatientLogin(JsonPath.read(res, "$.id"), JsonPath.read(res, "$.patientCode"), JsonPath.read(changed, "$.accessToken"), phone);
    }
}

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
                {"type":"%s","name":"Test %s","slug":"%s","ownerFirstName":"Owner","phone":"%s","password":"s3cretPass!"}
                """.formatted(type, slug, slug, phone);
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.status").value("TRIAL"))
                .andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone,
                JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    protected ResultActions onHost(String host, String token, String method, String path, String body) throws Exception {
        var b = switch (method) {
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        b.header("Host", host);
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

}

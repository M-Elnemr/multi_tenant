package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.core.auth.TotpService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** An owner can switch on authenticator-app codes: sign-in then needs the code, a code works once, recovery codes work once, and switching it off needs a code. */
class TwoStepSignInIntegrationTest extends IntegrationTestBase {
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String login(String phone, String code) { return "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"%s}".formatted(phone, code == null ? "" : ",\"totpCode\":\"" + code + "\""); }

    static String codeFor(String secret, long stepOffset) { return TotpService.otp(secret, Instant.now().getEpochSecond() / 30 + stepOffset); }

    @Test
    void signInNeedsTheCodeOnceItIsOnAndRecoveryCodesWorkOnce() throws Exception {
        Tenant shop = onboard("STORE");
        onHost("platform.test", shop.access(), "GET", "/api/v1/auth/2fa", null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        onHost("platform.test", null, "GET", "/api/v1/auth/2fa", null).andExpect(status().isUnauthorized());

        String secret = JsonPath.read(body(onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/setup", "{}").andExpect(status().isOk())), "$.secret");
        // a wrong first code does not switch it on; a right one does and returns the recovery codes
        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/enable", "{\"code\":\"000000\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
        String on = body(onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/enable", "{\"code\":\"%s\"}".formatted(codeFor(secret, 0))).andExpect(status().isOk()));
        List<String> recovery = JsonPath.read(on, "$.recoveryCodes");
        assertThat(recovery).hasSize(8);

        // the password alone is no longer enough
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), "123456")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
        // the code used to switch it on cannot be replayed; the next step's code works
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), codeFor(secret, 0))).andExpect(status().isUnauthorized());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), codeFor(secret, 1))).andExpect(status().isOk());
        // a recovery code signs in once
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), recovery.get(0))).andExpect(status().isOk());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), recovery.get(0))).andExpect(status().isUnauthorized());
    }

    @Test
    void switchingItOffNeedsACodeAndLogoutAllEndsTheSessions() throws Exception {
        Tenant shop = onboard("STORE");
        String secret = JsonPath.read(body(onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/setup", "{}")), "$.secret");
        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/enable", "{\"code\":\"%s\"}".formatted(codeFor(secret, 0))).andExpect(status().isOk());
        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/setup", "{}").andExpect(status().isConflict());

        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/disable", "{\"code\":\"\"}").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/2fa/disable", "{\"code\":\"%s\"}".formatted(codeFor(secret, 1))).andExpect(status().isOk());
        onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), null)).andExpect(status().isOk());

        String refresh = JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", login(shop.phone(), null))), "$.refreshToken");
        onHost("platform.test", shop.access(), "POST", "/api/v1/auth/logout-all", "{}").andExpect(status().isOk());
        onHost("platform.test", null, "POST", "/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refresh)).andExpect(status().isUnauthorized());
        onHost("platform.test", null, "POST", "/api/v1/auth/logout-all", "{}").andExpect(status().isUnauthorized());
    }
}

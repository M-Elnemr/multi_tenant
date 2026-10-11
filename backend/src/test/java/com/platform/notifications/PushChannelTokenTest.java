package com.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Regression: Firebase push never worked because the audience of the token request was a JSON array; Google requires one string. */
class PushChannelTokenTest {
    @Test
    void audienceOfTheGoogleTokenRequestIsASingleString() throws Exception {
        var kp = KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        String jwt = PushChannel.assertion("svc@project.iam.gserviceaccount.com", "https://oauth2.googleapis.com/token", kp.generateKeyPair().getPrivate(), Instant.now());
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        assertThat(claims.get("aud").isTextual()).isTrue();
        assertThat(claims.get("aud").asText()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(claims.get("iss").asText()).isEqualTo("svc@project.iam.gserviceaccount.com");
        assertThat(claims.get("scope").asText()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
    }
}

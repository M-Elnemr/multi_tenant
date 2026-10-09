package com.platform.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.BusinessException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Verifies the ID token with Google's own tokeninfo endpoint (Google checks the signature and expiry); we check audience and issuer. */
@Component
public class TokenInfoGoogleVerifier implements GoogleIdTokenVerifier {
    private final String clientId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public TokenInfoGoogleVerifier(@Value("${app.google.client-id:}") String clientId) { this.clientId = clientId == null || clientId.isBlank() ? null : clientId.trim(); }

    @Override public String clientId() { return clientId; }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId == null) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_NOT_CONFIGURED", "Google sign-in is not set up yet");
        if (idToken == null || idToken.length() < 20 || idToken.length() > 4096) throw invalid();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw invalid();
            JsonNode n = json.readTree(res.body());
            String iss = n.path("iss").asText("");
            boolean issuerOk = iss.equals("accounts.google.com") || iss.equals("https://accounts.google.com");
            if (!clientId.equals(n.path("aud").asText()) || !issuerOk || n.path("sub").asText("").isBlank()) throw invalid();
            if (n.hasNonNull("email") && !"true".equals(n.path("email_verified").asText("false"))) throw invalid();
            return new GoogleIdentity(n.path("sub").asText(), n.hasNonNull("email") ? n.path("email").asText().toLowerCase() : null, n.path("name").asText(""));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() { return BusinessException.unauthorized("GOOGLE_TOKEN_INVALID", "Google sign-in failed, please try again"); }
}

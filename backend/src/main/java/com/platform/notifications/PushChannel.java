package com.platform.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Phone notifications through Firebase Cloud Messaging (HTTP v1). Needs a Firebase service-account JSON file (FCM_CREDENTIALS_FILE);
 * without it the channel only logs, so nothing breaks before Firebase is set up. Tokens Firebase says are dead are removed.
 */
@Component
public class PushChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(PushChannel.class);

    private final JdbcClient jdbc;
    private final String credentialsFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile String accessToken;
    private volatile Instant accessExpiry = Instant.EPOCH;

    public PushChannel(JdbcClient jdbc, @Value("${app.fcm.credentials-file:}") String credentialsFile) {
        this.jdbc = jdbc;
        this.credentialsFile = credentialsFile == null ? "" : credentialsFile.trim();
    }

    @Override public String code() { return "PUSH"; }

    public boolean configured() { return !credentialsFile.isEmpty() && Files.isReadable(Path.of(credentialsFile)); }

    @Override
    public void send(String token, String title, String body) throws Exception {
        if (!configured()) {
            log.info("Push not configured (set FCM_CREDENTIALS_FILE); would send '{}' to a device", title);
            return;
        }
        JsonNode sa = json.readTree(Files.readString(Path.of(credentialsFile)));
        String url = "https://fcm.googleapis.com/v1/projects/" + sa.path("project_id").asText() + "/messages:send";
        Map<String, Object> message = Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", title, "body", body),
                "android", Map.of("priority", "HIGH", "notification", Map.of("sound", "default", "channel_id", "queue"))));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken(sa)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404 || res.statusCode() == 400 && res.body().contains("INVALID_ARGUMENT") && res.body().contains("registration token")) {
            jdbc.sql("DELETE FROM notifications.device_tokens WHERE token = :t").param("t", token).update();   // the app was uninstalled or the token rotated
            return;
        }
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("FCM " + res.statusCode() + ": " + res.body());
    }

    private synchronized String accessToken(JsonNode sa) throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessExpiry.minusSeconds(60))) return accessToken;
        String pem = sa.path("private_key").asText().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        String tokenUri = sa.path("token_uri").asText("https://oauth2.googleapis.com/token");
        Instant now = Instant.now();
        String assertion = Jwts.builder().issuer(sa.path("client_email").asText()).claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                .audience().add(tokenUri).and().issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3000))).signWith(key, Jwts.SIG.RS256).compact();
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) + "&assertion=" + assertion)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Google token " + res.statusCode());
        JsonNode n = json.readTree(res.body());
        accessToken = n.path("access_token").asText();
        accessExpiry = now.plusSeconds(n.path("expires_in").asLong(3000));
        return accessToken;
    }
}

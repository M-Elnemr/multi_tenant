package com.platform.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts connected-account credentials at rest (AES-256-GCM). Key: {@code app.integrations.key} (env INTEGRATIONS_KEY), or derived from the JWT secret when unset. */
@Component
public class IntegrationCrypto {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public IntegrationCrypto(@Value("${app.integrations.key:}") String configured, @Value("${app.jwt.secret}") String jwtSecret) {
        try {
            String seed = configured == null || configured.isBlank() ? "integrations|" + jwtSecret : configured;
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(Map<String, String> credentials) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(JSON.writeValueAsBytes(credentials));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, String> decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new LinkedHashMap<>(JSON.readValue(c.doFinal(all, 12, all.length - 12), new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the stored credentials", e);
        }
    }
}

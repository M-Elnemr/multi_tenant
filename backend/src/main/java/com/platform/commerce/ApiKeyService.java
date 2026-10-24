package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only API keys for a shop's own tools. The key is shown once; only its hash is kept. It works only on that shop's own address. */
@Service
public class ApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ApiKeyService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public static String hash(String key) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> create(UUID t, UUID actor, String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 80) throw BusinessException.badRequest("VALIDATION_ERROR", "Give the key a name");
        if (jdbc.sql("SELECT count(*) FROM commerce.api_keys WHERE tenant_id = :t AND revoked_at IS NULL").param("t", t).query(Long.class).single() >= 10) throw BusinessException.conflict("TOO_MANY_KEYS", "Revoke a key you no longer use first");
        StringBuilder k = new StringBuilder("mk_live_");
        for (int i = 0; i < 40; i++) k.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        String key = k.toString();
        UUID id = jdbc.sql("INSERT INTO commerce.api_keys (tenant_id, name, prefix, key_hash, created_by) VALUES (:t, :n, :p, :h, :u) RETURNING id").param("t", t).param("n", n).param("p", key.substring(0, 12)).param("h", hash(key)).param("u", actor).query(UUID.class).single();
        audit.record(actor, t, "API_KEY_CREATED", "api_key", id, null);
        return Map.of("id", id, "name", n, "key", key, "note", "Copy this key now. It is not shown again.");
    }

    public List<Map<String, Object>> list(UUID t) { return Rows.camel(jdbc.sql("SELECT id, name, prefix, created_at, last_used_at, revoked_at FROM commerce.api_keys WHERE tenant_id = :t ORDER BY created_at DESC").param("t", t).query().listOfRows()); }

    public void revoke(UUID t, UUID actor, UUID id) {
        if (jdbc.sql("UPDATE commerce.api_keys SET revoked_at = now() WHERE id = :i AND tenant_id = :t AND revoked_at IS NULL").param("i", id).param("t", t).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Key not found");
        audit.record(actor, t, "API_KEY_REVOKED", "api_key", id, null);
    }
}

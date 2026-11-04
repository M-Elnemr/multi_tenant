package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.core.rbac.RbacService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counter's terminals and the cashiers who use them. A terminal is a browser the manager registered once: it holds its own long-lived token (only its hash is kept here),
 * so it keeps selling and sending its sales whether or not anyone is signed in, and the manager can revoke it at any time. Cashiers switch on it with a PIN, which the terminal
 * checks with no internet: only a slow PBKDF2 hash of it ever leaves the server.
 */
@Service
public class PosDeviceService {
    /** A terminal the request came from (resolved from its token by {@code PosDeviceFilter}). */
    public record Device(UUID id, UUID tenantId, UUID branchId, String code, String name) {}

    static final int PIN_ITERATIONS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SIZES = Set.of("58", "80", "A4");

    private final JdbcClient jdbc;
    private final RbacService rbac;
    private final AuditService audit;

    public PosDeviceService(JdbcClient jdbc, RbacService rbac, AuditService audit) {
        this.jdbc = jdbc;
        this.rbac = rbac;
        this.audit = audit;
    }

    // ---- terminals -----------------------------------------------------------------------------------------------------

    /** Registers this browser as a terminal of the branch; the token is returned once (the web app keeps it in an httpOnly cookie). */
    @Transactional
    public Map<String, Object> register(UUID t, UUID actor, UUID branchId, String name) {
        UUID branch = branchId != null ? branchId : jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", t).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("NO_BRANCH", "Add a branch first"));
        if (jdbc.sql("SELECT count(*) FROM commerce.branches WHERE id = :b AND tenant_id = :t AND is_active").param("b", branch).param("t", t).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Branch not found");
        String label = name == null || name.isBlank() ? null : name.trim();
        if (label != null && label.length() > 80) label = label.substring(0, 80);
        int n = jdbc.sql("SELECT count(*) FROM commerce.pos_devices WHERE tenant_id = :t").param("t", t).query(Integer.class).single();
        String code = null;
        for (int i = n + 1; code == null; i++) {   // A1..A9, B1..: short, so invoice numbers stay short
            String c = (char) ('A' + Math.min(25, (i - 1) / 9)) + String.valueOf((i - 1) % 9 + 1) + (i > 234 ? String.valueOf(i) : "");
            if (jdbc.sql("SELECT count(*) FROM commerce.pos_devices WHERE tenant_id = :t AND code = :c").param("t", t).param("c", c).query(Long.class).single() == 0) code = c;
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "pd_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = jdbc.sql("INSERT INTO commerce.pos_devices (tenant_id, branch_id, name, code, token_hash, created_by) VALUES (:t, :b, :n, :c, :h, :u) RETURNING id")
                .param("t", t).param("b", branch).param("n", label == null ? "Terminal " + code : label).param("c", code).param("h", ApiKeyService.hash(token)).param("u", actor).query(UUID.class).single();
        audit.record(actor, t, "POS_DEVICE_REGISTERED", "pos_device", id, null);
        Map<String, Object> out = new LinkedHashMap<>(device(t, id));
        out.put("token", token);
        return out;
    }

    public List<Map<String, Object>> list(UUID t) {
        return Rows.camel(jdbc.sql("""
                SELECT d.id, d.name, d.code, d.branch_id, b.name AS branch_name, d.receipt_size, d.last_seen_at, d.last_seq, d.revoked_at, d.created_at,
                       (SELECT count(*) FROM commerce.pos_sync_issues i WHERE i.device_id = d.id AND i.resolved_at IS NULL) AS open_issues
                FROM commerce.pos_devices d JOIN commerce.branches b ON b.id = d.branch_id WHERE d.tenant_id = :t ORDER BY d.revoked_at NULLS FIRST, d.created_at
                """).param("t", t).query().listOfRows());
    }

    Map<String, Object> device(UUID t, UUID id) {
        return jdbc.sql("SELECT d.id, d.name, d.code, d.branch_id, b.name AS branch_name, d.receipt_size, d.revoked_at FROM commerce.pos_devices d JOIN commerce.branches b ON b.id = d.branch_id WHERE d.id = :i AND d.tenant_id = :t")
                .param("i", id).param("t", t).query().listOfRows().stream().findFirst().map(Rows::camel).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Terminal not found"));
    }

    public Map<String, Object> update(UUID t, UUID actor, UUID id, String name, String receiptSize) {
        device(t, id);
        if (receiptSize != null && !receiptSize.isBlank() && !SIZES.contains(receiptSize)) throw BusinessException.badRequest("VALIDATION_ERROR", "Receipt size is 58, 80 or A4");
        if (name != null && !name.isBlank()) jdbc.sql("UPDATE commerce.pos_devices SET name = :n WHERE id = :i").param("n", name.trim().length() > 80 ? name.trim().substring(0, 80) : name.trim()).param("i", id).update();
        if (receiptSize != null) jdbc.sql("UPDATE commerce.pos_devices SET receipt_size = :s WHERE id = :i").param("s", receiptSize.isBlank() ? null : receiptSize).param("i", id).update();
        audit.record(actor, t, "POS_DEVICE_UPDATED", "pos_device", id, null);
        return device(t, id);
    }

    public void revoke(UUID t, UUID actor, UUID id) {
        device(t, id);
        jdbc.sql("UPDATE commerce.pos_devices SET revoked_at = coalesce(revoked_at, now()) WHERE id = :i").param("i", id).update();
        audit.record(actor, t, "POS_DEVICE_REVOKED", "pos_device", id, null);
    }

    /** The terminal a token belongs to on this shop's address, or null (unknown, revoked, or another shop's). */
    public Device authenticate(UUID tenantId, String token) {
        if (token == null || token.isBlank() || token.length() > 200) return null;
        var d = jdbc.sql("SELECT id, tenant_id, branch_id, code, name FROM commerce.pos_devices WHERE token_hash = :h AND tenant_id = :t AND revoked_at IS NULL")
                .param("h", ApiKeyService.hash(token.trim())).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (d == null) return null;
        jdbc.sql("UPDATE commerce.pos_devices SET last_seen_at = now() WHERE id = :i AND (last_seen_at IS NULL OR last_seen_at < now() - interval '1 minute')").param("i", d.get("id")).update();
        return new Device((UUID) d.get("id"), (UUID) d.get("tenant_id"), (UUID) d.get("branch_id"), (String) d.get("code"), (String) d.get("name"));
    }

    // ---- cashier PINs --------------------------------------------------------------------------------------------------

    /** Sets a cashier's PIN (4 to 6 digits); unique in the shop, so a PIN alone tells who is switching in. */
    public void setPin(UUID t, UUID actor, UUID userId, String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) throw BusinessException.badRequest("VALIDATION_ERROR", "The PIN is 4 to 6 digits");
        if (jdbc.sql("SELECT count(*) FROM core.user_tenant_memberships WHERE tenant_id = :t AND user_id = :u AND status = 'ACTIVE'").param("t", t).param("u", userId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "This person is not on the team");
        for (var other : jdbc.sql("SELECT user_id, salt, iterations, pin_hash FROM commerce.pos_pins WHERE tenant_id = :t AND user_id <> :u").param("t", t).param("u", userId).query().listOfRows())
            if (hashPin(pin, (String) other.get("salt"), ((Number) other.get("iterations")).intValue()).equals(other.get("pin_hash")))
                throw BusinessException.conflict("PIN_TAKEN", "Another cashier already uses this PIN");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String s = HexFormat.of().formatHex(salt);
        jdbc.sql("""
                INSERT INTO commerce.pos_pins (tenant_id, user_id, salt, iterations, pin_hash) VALUES (:t, :u, :s, :i, :h)
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET salt = EXCLUDED.salt, iterations = EXCLUDED.iterations, pin_hash = EXCLUDED.pin_hash, updated_at = now()
                """).param("t", t).param("u", userId).param("s", s).param("i", PIN_ITERATIONS).param("h", hashPin(pin, s, PIN_ITERATIONS)).update();
        audit.record(actor, t, "POS_PIN_SET", "user", userId, null);
    }

    public void clearPin(UUID t, UUID actor, UUID userId) {
        jdbc.sql("DELETE FROM commerce.pos_pins WHERE tenant_id = :t AND user_id = :u").param("t", t).param("u", userId).update();
        audit.record(actor, t, "POS_PIN_CLEARED", "user", userId, null);
    }

    /** PBKDF2-SHA256 of the PIN, hex: the terminal computes exactly the same with WebCrypto. */
    static String hashPin(String pin, String saltHex, int iterations) {
        try {
            var spec = new PBEKeySpec(pin.toCharArray(), HexFormat.of().parseHex(saltHex), iterations, 256);
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The team members who may use the till, with what the terminal needs to check their PIN and what they may do there. */
    public List<Map<String, Object>> cashiers(UUID t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var m : jdbc.sql("""
                SELECT m.id AS membership_id, u.id AS user_id, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS name, p.salt, p.iterations, p.pin_hash
                FROM core.user_tenant_memberships m JOIN core.users u ON u.id = m.user_id LEFT JOIN commerce.pos_pins p ON p.tenant_id = m.tenant_id AND p.user_id = u.id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND u.status <> 'DISABLED' ORDER BY u.first_name
                """).param("t", t).query().listOfRows()) {
            Set<String> perms = rbac.permissionCodes((UUID) m.get("membership_id"));
            if (!perms.contains("pos.use")) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", m.get("user_id"));
            c.put("name", m.get("name"));
            c.put("hasPin", m.get("pin_hash") != null);
            c.put("salt", m.get("salt"));
            c.put("iterations", m.get("iterations"));
            c.put("pinHash", m.get("pin_hash"));
            c.put("canRefund", perms.contains("refund.create"));
            c.put("canCash", perms.contains("pos.cash"));
            c.put("canManage", perms.contains("pos.manage"));
            c.put("canCount", perms.contains("inventory.adjust") || perms.contains("pos.manage"));
            out.add(c);
        }
        return out;
    }

    /** The team list for the dashboard: who may use the till and whether they have a PIN yet (no hashes). */
    public List<Map<String, Object>> team(UUID t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : cashiers(t)) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.remove("salt"); m.remove("iterations"); m.remove("pinHash");
            out.add(m);
        }
        return out;
    }
}

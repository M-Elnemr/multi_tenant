package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Points for delivered orders. A point is worth one currency unit; the owner picks the percentage of the goods value that comes back as points. Points are
 * spent by turning them into a one-time coupon, so checkout, pricing and limits stay exactly as they are. Off until the owner switches it on.
 */
@Service
public class LoyaltyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public LoyaltyService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> settings(UUID t) {
        var r = jdbc.sql("SELECT enabled, earn_percent, min_redeem FROM commerce.loyalty_settings WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r != null && Boolean.TRUE.equals(r.get("enabled")));
        m.put("earnPercent", r == null ? 5 : r.get("earn_percent"));
        m.put("minRedeem", r == null ? 20 : r.get("min_redeem"));
        return m;
    }

    public Map<String, Object> saveSettings(UUID t, UUID actor, Boolean enabled, Integer earnPercent, Integer minRedeem) {
        var cur = settings(t);
        boolean e = enabled != null ? enabled : (Boolean) cur.get("enabled");
        int p = earnPercent != null ? earnPercent : ((Number) cur.get("earnPercent")).intValue();
        int m = minRedeem != null ? minRedeem : ((Number) cur.get("minRedeem")).intValue();
        if (p < 1 || p > 50) throw BusinessException.badRequest("VALIDATION_ERROR", "Points percentage must be between 1 and 50");
        if (m < 1 || m > 100000) throw BusinessException.badRequest("VALIDATION_ERROR", "Minimum points to redeem is not valid");
        jdbc.sql("INSERT INTO commerce.loyalty_settings (tenant_id, enabled, earn_percent, min_redeem) VALUES (:t, :e, :p, :m) ON CONFLICT (tenant_id) DO UPDATE SET enabled = :e, earn_percent = :p, min_redeem = :m, updated_at = now()")
                .param("t", t).param("e", e).param("p", p).param("m", m).update();
        audit.record(actor, t, "SETTINGS_CHANGED", "loyalty", null, null);
        return settings(t);
    }

    /** An order just arrived: its customer earns points (once per order). */
    @Transactional
    public void earnForDelivered(UUID tenantId, UUID orderId, String phone, long subtotalMinor, long discountMinor) {
        var s = settings(tenantId);
        if (!(Boolean) s.get("enabled") || phone == null) return;
        long goods = Math.max(0, subtotalMinor - discountMinor);
        int points = (int) (goods / 100 * ((Number) s.get("earnPercent")).intValue() / 100);
        if (points <= 0) return;
        if (jdbc.sql("INSERT INTO commerce.loyalty_ledger (tenant_id, phone, delta, reason, order_id) VALUES (:t, :p, :d, 'EARN', :o) ON CONFLICT DO NOTHING").param("t", tenantId).param("p", phone).param("d", points).param("o", orderId).update() == 0) return;
        jdbc.sql("INSERT INTO commerce.loyalty_accounts (tenant_id, phone, points, lifetime_points) VALUES (:t, :p, :d, :d) ON CONFLICT (tenant_id, phone) DO UPDATE SET points = commerce.loyalty_accounts.points + :d, lifetime_points = commerce.loyalty_accounts.lifetime_points + :d, updated_at = now()")
                .param("t", tenantId).param("p", phone).param("d", points).update();
    }

    /** A delivered order came back: the points it gave are taken back (never below zero). */
    @Transactional
    public void reverseForReturned(UUID tenantId, UUID orderId, String phone) {
        Integer earned = jdbc.sql("SELECT delta FROM commerce.loyalty_ledger WHERE order_id = :o AND reason = 'EARN'").param("o", orderId).query(Integer.class).optional().orElse(null);
        if (earned == null) return;
        if (jdbc.sql("INSERT INTO commerce.loyalty_ledger (tenant_id, phone, delta, reason, order_id) VALUES (:t, :p, :d, 'REVERSE', :o) ON CONFLICT DO NOTHING").param("t", tenantId).param("p", phone).param("d", -earned).param("o", orderId).update() == 0) return;
        jdbc.sql("UPDATE commerce.loyalty_accounts SET points = greatest(0, points - :d), updated_at = now() WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", phone).param("d", earned).update();
    }

    private String phoneOf(UUID userId) {
        String raw = jdbc.sql("SELECT phone FROM commerce.client_accounts WHERE id = :u").param("u", userId).query(String.class).optional().orElse(null);
        if (raw == null || raw.isBlank()) return null;
        try { return PhoneNormalizer.normalize(raw.replace(" ", "")); } catch (BusinessException e) { return null; }
    }

    /** What the signed-in shopper sees in their account. */
    public Map<String, Object> mine(UUID tenantId, UUID userId) {
        var s = settings(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.get("enabled"));
        m.put("earnPercent", s.get("earnPercent"));
        m.put("minRedeem", s.get("minRedeem"));
        String phone = phoneOf(userId);
        var acc = phone == null ? null : jdbc.sql("SELECT points, lifetime_points FROM commerce.loyalty_accounts WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", phone).query().listOfRows().stream().findFirst().orElse(null);
        m.put("points", acc == null ? 0 : acc.get("points"));
        m.put("lifetimePoints", acc == null ? 0 : acc.get("lifetime_points"));
        m.put("history", phone == null ? List.of() : Rows.camel(jdbc.sql("SELECT delta, reason, coupon_code, created_at FROM commerce.loyalty_ledger WHERE tenant_id = :t AND phone = :p ORDER BY id DESC LIMIT 20").param("t", tenantId).param("p", phone).query().listOfRows()));
        return m;
    }

    /** Turns points into a one-time coupon worth that many currency units, valid 60 days, for this shop only. */
    @Transactional
    public Map<String, Object> redeem(UUID tenantId, UUID userId, int points) {
        var s = settings(tenantId);
        if (!(Boolean) s.get("enabled")) throw BusinessException.badRequest("LOYALTY_OFF", "This shop does not use points");
        String phone = phoneOf(userId);
        if (phone == null) throw BusinessException.badRequest("PHONE_REQUIRED", "Add your mobile number to your account first");
        if (points < ((Number) s.get("minRedeem")).intValue()) throw BusinessException.badRequest("BELOW_MINIMUM", "The minimum to redeem is " + s.get("minRedeem") + " points");
        if (jdbc.sql("UPDATE commerce.loyalty_accounts SET points = points - :d, updated_at = now() WHERE tenant_id = :t AND phone = :p AND points >= :d").param("t", tenantId).param("p", phone).param("d", points).update() == 0)
            throw BusinessException.badRequest("NOT_ENOUGH_POINTS", "You do not have that many points");
        StringBuilder code = new StringBuilder("LP-");
        for (int i = 0; i < 8; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        jdbc.sql("INSERT INTO commerce.coupons (tenant_id, code, discount_type, value, min_order_minor, ends_at, max_redemptions, per_customer_limit) VALUES (:t, :c, 'FIXED', :v, :v, :e, 1, 1)")
                .param("t", tenantId).param("c", code.toString()).param("v", points * 100L).param("e", Timestamp.from(Instant.now().plus(Duration.ofDays(60)))).update();
        jdbc.sql("INSERT INTO commerce.loyalty_ledger (tenant_id, phone, delta, reason, coupon_code) VALUES (:t, :p, :d, 'REDEEM', :c)").param("t", tenantId).param("p", phone).param("d", -points).param("c", code.toString()).update();
        return Map.of("couponCode", code.toString(), "valueMinor", points * 100L, "validDays", 60);
    }

    /** The owner's view: best customers by points. */
    public List<Map<String, Object>> top(UUID tenantId) {
        return Rows.camel(jdbc.sql("""
                SELECT a.phone, a.points, a.lifetime_points, (SELECT customer_name_snapshot FROM commerce.orders o WHERE o.tenant_id = a.tenant_id AND o.customer_phone_snapshot = a.phone ORDER BY created_at DESC LIMIT 1) AS name
                FROM commerce.loyalty_accounts a WHERE a.tenant_id = :t ORDER BY a.lifetime_points DESC LIMIT 50
                """).param("t", tenantId).query().listOfRows());
    }
}

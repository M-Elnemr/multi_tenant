package com.platform.affiliate;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a marketer does: browse the marketplace, apply to shops, get links, follow their numbers, request payouts, rate, chat and open disputes. */
@Service
public class MarketerService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient jdbc;
    private final AffiliateService core;
    private final AffiliateAudit audit;

    public MarketerService(JdbcClient jdbc, AffiliateService core, AffiliateAudit audit) { this.jdbc = jdbc; this.core = core; this.audit = audit; }

    private static final String HOST = "(SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary)";

    /** Shops that opted in, with their terms, rating and how this marketer stands with each. */
    public Map<String, Object> marketplace(UUID account, String q, int page, int size) {
        boolean open = jdbc.sql("SELECT marketplace_open FROM affiliate.settings WHERE id = 1").query(Boolean.class).single();
        if (!open) return Map.of("data", List.of(), "total", 0, "page", page, "size", size, "closed", true);
        int s = Math.max(1, Math.min(size, 50)), off = Math.max(0, page) * s;
        String q2 = q == null || q.isBlank() ? null : "%" + q.strip() + "%";
        String from = " FROM affiliate.programs g JOIN core.tenants t ON t.id = g.tenant_id WHERE g.enabled AND g.listed AND t.status IN ('ACTIVE','TRIAL') AND (CAST(:q AS text) IS NULL OR t.name ILIKE :q OR g.pitch ILIKE :q)";
        var rows = Rows.camel(jdbc.sql("SELECT t.id, t.name, t.slug, t.tenant_type, " + HOST + " AS host, g.percent, g.window_days, g.hold_days, g.min_payout_minor, g.pitch, g.terms, g.auto_approve, " +
                "(SELECT count(*) FROM affiliate.partners p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS marketers, " +
                "(SELECT round(avg(r.rating)::numeric, 1) FROM affiliate.reviews r WHERE r.tenant_id = t.id AND r.direction = 'MARKETER_TO_SHOP') AS rating, " +
                "(SELECT count(*) FROM affiliate.reviews r WHERE r.tenant_id = t.id AND r.direction = 'MARKETER_TO_SHOP') AS rating_count, " +
                "(SELECT p.status FROM affiliate.partners p WHERE p.tenant_id = t.id AND p.account_id = :a) AS my_status" + from + " ORDER BY g.percent DESC, t.name LIMIT :l OFFSET :o")
                .param("q", q2).param("a", account).param("l", s).param("o", off).query().listOfRows());
        long total = jdbc.sql("SELECT count(*)" + from).param("q", q2).query(Long.class).single();
        return Map.of("data", rows, "total", total, "page", page, "size", s);
    }

    @Transactional
    public Map<String, Object> apply(UUID account, UUID tenant, String message) {
        var acc = jdbc.sql("SELECT name, phone FROM affiliate.accounts WHERE id = :a").param("a", account).query().singleRow();
        var prog = jdbc.sql("SELECT g.auto_approve FROM affiliate.programs g JOIN core.tenants t ON t.id = g.tenant_id WHERE g.tenant_id = :t AND g.enabled AND g.listed AND t.status IN ('ACTIVE','TRIAL')").param("t", tenant).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notListed", "This shop is not taking marketers"));
        var existing = jdbc.sql("SELECT status FROM affiliate.partners WHERE tenant_id = :t AND account_id = :a").param("t", tenant).param("a", account).query(String.class).optional();
        if (existing.isPresent()) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.alreadyApplied", "You already applied to this shop (" + existing.get() + ")");
        String code = null;
        for (int i = 0; i < 20 && code == null; i++) {
            String c = newCode();
            if (jdbc.sql("SELECT count(*) FROM affiliate.partners WHERE tenant_id = :t AND code = :c").param("t", tenant).param("c", c).query(Long.class).single() == 0) code = c;
        }
        if (code == null) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.code", "Try again");
        boolean auto = Boolean.TRUE.equals(prog.get("auto_approve"));
        UUID id = jdbc.sql("INSERT INTO affiliate.partners (tenant_id, account_id, name, phone, code, status, message, decided_at) VALUES (:t, :a, :n, :p, :c, :s, :m, CASE WHEN :auto THEN now() END) RETURNING id")
                .param("t", tenant).param("a", account).param("n", acc.get("name")).param("p", acc.get("phone")).param("c", code).param("s", auto ? "ACTIVE" : "PENDING").param("m", ShopAffiliate.cut(message, 500)).param("auto", auto).query(UUID.class).single();
        audit.log("MARKETER", account, tenant, id, "APPLIED", auto ? "auto-approved" : "waiting for the shop");
        return Map.of("partnerId", id, "status", auto ? "ACTIVE" : "PENDING", "code", code);
    }

    private UUID mine(UUID account, UUID partnerId) {
        return jdbc.sql("SELECT tenant_id FROM affiliate.partners WHERE id = :p AND account_id = :a").param("p", partnerId).param("a", account).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Not found"));
    }

    /** Every shop this marketer is attached to, with the effective rate, the link and their numbers. */
    public List<Map<String, Object>> myShops(UUID account) {
        return Rows.camel(jdbc.sql("SELECT p.id, p.status, p.code, p.created_at, t.id AS tenant_id, t.name AS shop, " + HOST + " AS host, coalesce(p.percent, g.percent) AS percent, g.window_days, g.hold_days, g.min_payout_minor, g.terms, " +
                "(SELECT count(*) FROM affiliate.visits v WHERE v.partner_id = p.id) AS visits, " +
                "count(c.id) FILTER (WHERE c.status <> 'REVERSED') AS orders, coalesce(sum(c.base_minor) FILTER (WHERE c.status <> 'REVERSED'), 0) AS sales_minor, " +
                "coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PENDING'), 0) AS pending_minor, coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'APPROVED'), 0) AS approved_minor, " +
                "coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'APPROVED' AND c.payout_request_id IS NULL), 0) AS available_minor, " +
                "coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PAID'), 0) AS paid_minor, coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'REVERSED'), 0) AS reversed_minor, " +
                "(SELECT count(*) FROM affiliate.messages m WHERE m.partner_id = p.id AND m.sender = 'SHOP' AND m.read_at IS NULL) AS unread " +
                "FROM affiliate.partners p JOIN core.tenants t ON t.id = p.tenant_id LEFT JOIN affiliate.programs g ON g.tenant_id = p.tenant_id LEFT JOIN affiliate.commissions c ON c.partner_id = p.id " +
                "WHERE p.account_id = :a GROUP BY p.id, t.id, g.tenant_id ORDER BY p.created_at DESC").param("a", account).query().listOfRows());
    }

    public Map<String, Object> link(UUID account, UUID partnerId, String campaign) {
        mine(account, partnerId);
        var row = jdbc.sql("SELECT p.code, p.status, " + HOST + " AS host FROM affiliate.partners p JOIN core.tenants t ON t.id = p.tenant_id WHERE p.id = :p").param("p", partnerId).query().singleRow();
        if (!"ACTIVE".equals(row.get("status"))) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.notActive", "The link works after the shop approves you");
        String camp = AffiliateService.clean(campaign);
        String ref = row.get("code") + (camp == null ? "" : ":" + camp);
        return Map.of("url", "https://" + row.get("host") + "/?ref=" + ref, "ref", ref);
    }

    public Map<String, Object> stats(UUID account) {
        var totals = Rows.camel(jdbc.sql("""
                SELECT count(c.id) FILTER (WHERE c.status <> 'REVERSED') AS orders, coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PENDING'), 0) AS pending_minor,
                       coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'APPROVED'), 0) AS approved_minor, coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PAID'), 0) AS paid_minor,
                       coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'REVERSED'), 0) AS reversed_minor,
                       (SELECT count(*) FROM affiliate.visits v JOIN affiliate.partners x ON x.id = v.partner_id WHERE x.account_id = :a) AS clicks
                FROM affiliate.partners p LEFT JOIN affiliate.commissions c ON c.partner_id = p.id WHERE p.account_id = :a
                """).param("a", account).query().singleRow());
        Map<String, Object> out = new LinkedHashMap<>(totals);
        long clicks = ((Number) totals.get("clicks")).longValue(), orders = ((Number) totals.get("orders")).longValue();
        out.put("conversionPercent", clicks == 0 ? 0 : Math.round(orders * 1000.0 / clicks) / 10.0);
        out.put("byCampaign", Rows.camel(jdbc.sql("""
                SELECT coalesce(c.campaign, '-') AS campaign, count(*) FILTER (WHERE c.status <> 'REVERSED') AS orders, coalesce(sum(c.amount_minor) FILTER (WHERE c.status <> 'REVERSED'), 0) AS amount_minor
                FROM affiliate.commissions c JOIN affiliate.partners p ON p.id = c.partner_id WHERE p.account_id = :a GROUP BY 1 ORDER BY 2 DESC LIMIT 30
                """).param("a", account).query().listOfRows()));
        return out;
    }

    public List<Map<String, Object>> commissions(UUID account, UUID partnerId, String status, int limit) {
        return Rows.camel(jdbc.sql("SELECT c.id, c.order_number, c.base_minor, c.percent, c.amount_minor, c.status, c.campaign, c.hold_until, c.created_at, t.name AS shop FROM affiliate.commissions c JOIN affiliate.partners p ON p.id = c.partner_id JOIN core.tenants t ON t.id = c.tenant_id " +
                "WHERE p.account_id = :a AND (CAST(:p AS uuid) IS NULL OR p.id = :p) AND (CAST(:s AS text) IS NULL OR c.status = :s) ORDER BY c.created_at DESC LIMIT :l")
                .param("a", account).param("p", partnerId).param("s", ShopAffiliate.blank(status)).param("l", Math.max(1, Math.min(limit, 300))).query().listOfRows());
    }

    public List<Map<String, Object>> offers(UUID account) {
        return Rows.camel(jdbc.sql("SELECT o.id, o.title, o.percent, o.starts_at, o.ends_at, t.name AS shop, p.id AS partner_id FROM affiliate.offers o JOIN affiliate.partners p ON p.tenant_id = o.tenant_id AND p.account_id = :a AND p.status = 'ACTIVE' JOIN core.tenants t ON t.id = o.tenant_id " +
                "WHERE o.active AND o.starts_at <= now() AND (o.ends_at IS NULL OR o.ends_at > now()) AND (o.partner_id IS NULL OR o.partner_id = p.id) ORDER BY o.percent DESC").param("a", account).query().listOfRows());
    }

    // ---- payouts -----------------------------------------------------------------------------------------------------------------

    /** A request for the shop to pay the approved balance. It pays nothing: the shop settles it (or declines it) and records how. */
    @Transactional
    public Map<String, Object> requestPayout(UUID account, UUID partnerId, String note) {
        UUID tenant = mine(account, partnerId);
        if (jdbc.sql("SELECT count(*) FROM affiliate.payout_requests WHERE partner_id = :p AND status = 'REQUESTED'").param("p", partnerId).query(Long.class).single() > 0)
            throw new BusinessException(HttpStatus.CONFLICT, "affiliate.openRequest", "You already have an open payout request for this shop");
        long min = ((Number) core.program(tenant).get("minPayoutMinor")).longValue();
        var due = jdbc.sql("SELECT id, amount_minor FROM affiliate.commissions WHERE partner_id = :p AND status = 'APPROVED' AND payout_request_id IS NULL FOR UPDATE").param("p", partnerId).query().listOfRows();
        long total = due.stream().mapToLong(r -> ((Number) r.get("amount_minor")).longValue()).sum();
        if (due.isEmpty() || total <= 0) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.nothingDue", "You have no approved balance to request");
        if (total < min) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.belowMinimum", "The minimum payout for this shop is " + min / 100.0);
        UUID req = jdbc.sql("INSERT INTO affiliate.payout_requests (tenant_id, partner_id, amount_minor, note) VALUES (:t, :p, :a, :n) RETURNING id").param("t", tenant).param("p", partnerId).param("a", total).param("n", ShopAffiliate.cut(note, 300)).query(UUID.class).single();
        for (var r : due) {
            jdbc.sql("UPDATE affiliate.commissions SET payout_request_id = :r WHERE id = :id").param("r", req).param("id", r.get("id")).update();
            core.ledger(tenant, (UUID) r.get("id"), "LOCK", 0, "Included in a payout request");
        }
        audit.log("MARKETER", account, tenant, partnerId, "PAYOUT_REQUESTED", total + " minor units");
        return Map.of("id", req, "amountMinor", total, "status", "REQUESTED");
    }

    @Transactional
    public void cancelPayout(UUID account, UUID requestId) {
        var r = jdbc.sql("SELECT r.tenant_id, r.partner_id FROM affiliate.payout_requests r JOIN affiliate.partners p ON p.id = r.partner_id WHERE r.id = :i AND p.account_id = :a AND r.status = 'REQUESTED' FOR UPDATE OF r").param("i", requestId).param("a", account).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "affiliate.notOpen", "This request is not open"));
        for (var c : jdbc.sql("SELECT id FROM affiliate.commissions WHERE payout_request_id = :r AND status = 'APPROVED'").param("r", requestId).query().listOfRows()) core.ledger((UUID) r.get("tenant_id"), (UUID) c.get("id"), "UNLOCK", 0, "The marketer cancelled the payout request");
        jdbc.sql("UPDATE affiliate.commissions SET payout_request_id = NULL WHERE payout_request_id = :r AND status = 'APPROVED'").param("r", requestId).update();
        jdbc.sql("UPDATE affiliate.payout_requests SET status = 'CANCELLED', decided_at = now() WHERE id = :i").param("i", requestId).update();
        audit.log("MARKETER", account, (UUID) r.get("tenant_id"), (UUID) r.get("partner_id"), "PAYOUT_CANCELLED", requestId.toString());
    }

    public List<Map<String, Object>> payouts(UUID account) {
        return Rows.camel(jdbc.sql("SELECT r.id, r.amount_minor, r.status, r.note, r.evidence, r.decision_note, r.requested_at, r.decided_at, t.name AS shop FROM affiliate.payout_requests r JOIN affiliate.partners p ON p.id = r.partner_id JOIN core.tenants t ON t.id = r.tenant_id WHERE p.account_id = :a ORDER BY r.requested_at DESC LIMIT 200").param("a", account).query().listOfRows());
    }

    // ---- reviews, chat, disputes, notifications ----------------------------------------------------------------------------------

    public Map<String, Object> rateShop(UUID account, UUID partnerId, int rating, String comment) {
        UUID tenant = mine(account, partnerId);
        if (rating < 1 || rating > 5) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.rating", "The rating is 1 to 5");
        if (jdbc.sql(ShopAffiliate.RELATION).param("t", tenant).param("p", partnerId).query(Long.class).single() == 0) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.noRelation", "You can rate a shop after at least one of your orders is approved");
        jdbc.sql("INSERT INTO affiliate.reviews (tenant_id, partner_id, direction, rating, comment) VALUES (:t, :p, 'MARKETER_TO_SHOP', :r, :c) ON CONFLICT (tenant_id, partner_id, direction) DO UPDATE SET rating = :r, comment = :c, created_at = now()")
                .param("t", tenant).param("p", partnerId).param("r", rating).param("c", ShopAffiliate.cut(comment, 500)).update();
        audit.log("MARKETER", account, tenant, partnerId, "RATED_SHOP", String.valueOf(rating));
        return Map.of("rating", rating);
    }

    public List<Map<String, Object>> thread(UUID account, UUID partnerId) {
        mine(account, partnerId);
        jdbc.sql("UPDATE affiliate.messages SET read_at = now() WHERE partner_id = :p AND sender = 'SHOP' AND read_at IS NULL").param("p", partnerId).update();
        return Rows.camel(jdbc.sql("SELECT id, sender, body, created_at FROM affiliate.messages WHERE partner_id = :p ORDER BY id DESC LIMIT 100").param("p", partnerId).query().listOfRows());
    }

    public void send(UUID account, UUID partnerId, String body) {
        UUID tenant = mine(account, partnerId);
        var st = jdbc.sql("SELECT status FROM affiliate.partners WHERE id = :p").param("p", partnerId).query(String.class).single();
        if ("BLOCKED".equals(st) || "REJECTED".equals(st)) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.blocked", "You cannot message this shop");
        if (jdbc.sql("SELECT count(*) FROM affiliate.messages WHERE partner_id = :p AND sender = 'MARKETER' AND created_at > now() - interval '10 minutes'").param("p", partnerId).query(Long.class).single() >= 20)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "affiliate.slowDown", "Slow down a little");
        jdbc.sql("INSERT INTO affiliate.messages (tenant_id, partner_id, sender, body) VALUES (:t, :p, 'MARKETER', :b)").param("t", tenant).param("p", partnerId).param("b", ShopAffiliate.message(body)).update();
    }

    public void openDispute(UUID account, UUID partnerId, UUID commissionId, String reason) {
        UUID tenant = mine(account, partnerId);
        if (reason == null || reason.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.reason", "Write the reason");
        if (commissionId != null && jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE id = :c AND partner_id = :p").param("c", commissionId).param("p", partnerId).query(Long.class).single() == 0)
            throw new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Commission not found");
        jdbc.sql("INSERT INTO affiliate.disputes (tenant_id, partner_id, commission_id, opened_by, reason) VALUES (:t, :p, :c, 'MARKETER', :r)").param("t", tenant).param("p", partnerId).param("c", commissionId).param("r", ShopAffiliate.cut(reason, 600)).update();
        audit.log("MARKETER", account, tenant, partnerId, "DISPUTE_OPENED", reason);
    }

    public List<Map<String, Object>> disputes(UUID account) {
        return Rows.camel(jdbc.sql("SELECT d.id, d.opened_by, d.reason, d.status, d.resolution, d.created_at, d.resolved_at, t.name AS shop FROM affiliate.disputes d JOIN affiliate.partners p ON p.id = d.partner_id JOIN core.tenants t ON t.id = d.tenant_id WHERE p.account_id = :a ORDER BY d.created_at DESC LIMIT 100").param("a", account).query().listOfRows());
    }

    public Map<String, Object> notifications(UUID account) {
        var list = Rows.camel(jdbc.sql("SELECT id, kind, text, created_at, read_at FROM affiliate.notifications WHERE account_id = :a ORDER BY id DESC LIMIT 60").param("a", account).query().listOfRows());
        long unread = jdbc.sql("SELECT count(*) FROM affiliate.notifications WHERE account_id = :a AND read_at IS NULL").param("a", account).query(Long.class).single();
        return Map.of("data", list, "unread", unread);
    }

    public void readNotifications(UUID account) { jdbc.sql("UPDATE affiliate.notifications SET read_at = now() WHERE account_id = :a AND read_at IS NULL").param("a", account).update(); }

    private static String newCode() {
        StringBuilder b = new StringBuilder(8);
        for (int i = 0; i < 8; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }
}

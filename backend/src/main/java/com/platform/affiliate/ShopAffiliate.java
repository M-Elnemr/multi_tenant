package com.platform.affiliate;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The shop's side of the marketer relationship: applications, offers, payout requests, reviews, chat and disputes. Every call is scoped to the shop it came from. */
@Service
public class ShopAffiliate {
    private final JdbcClient jdbc;
    private final AffiliateService core;
    private final AffiliateAudit audit;

    public ShopAffiliate(JdbcClient jdbc, AffiliateService core, AffiliateAudit audit) { this.jdbc = jdbc; this.core = core; this.audit = audit; }

    private Map<String, Object> partner(UUID t, UUID id) { return core.partner(t, id); }

    // ---- applications ------------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> applications(UUID t, String status) {
        return Rows.camel(jdbc.sql("""
                SELECT p.id, p.name, p.phone, p.code, p.status, p.message, p.created_at, p.decided_at, a.city, a.bio, a.channels,
                       (SELECT round(avg(rating)::numeric, 1) FROM affiliate.reviews r WHERE r.partner_id IN (SELECT id FROM affiliate.partners WHERE account_id = p.account_id) AND r.direction = 'SHOP_TO_MARKETER') AS rating
                FROM affiliate.partners p LEFT JOIN affiliate.accounts a ON a.id = p.account_id
                WHERE p.tenant_id = :t AND p.account_id IS NOT NULL AND (CAST(:s AS text) IS NULL OR p.status = :s) ORDER BY p.created_at DESC LIMIT 200
                """).param("t", t).param("s", status == null || status.isBlank() ? null : status).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> decide(UUID t, UUID partnerId, String decision) {
        var p = partner(t, partnerId);
        String cur = (String) p.get("status");
        String next = switch (decision) { case "APPROVE" -> "ACTIVE"; case "REJECT" -> "REJECTED"; case "BLOCK" -> "BLOCKED"; case "UNBLOCK" -> "ACTIVE"; default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.decision", "Decision is APPROVE, REJECT, BLOCK or UNBLOCK"); };
        if (("APPROVE".equals(decision) || "REJECT".equals(decision)) && !"PENDING".equals(cur)) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.notPending", "This application was already decided");
        if ("UNBLOCK".equals(decision) && !"BLOCKED".equals(cur)) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.notBlocked", "This marketer is not blocked");
        jdbc.sql("UPDATE affiliate.partners SET status = :s, decided_at = now() WHERE id = :i AND tenant_id = :t").param("s", next).param("i", partnerId).param("t", t).update();
        audit.log("SHOP", null, t, partnerId, "APPLICATION_" + decision, null);
        var acc = jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :i").param("i", partnerId).query(UUID.class).optional().orElse(null);
        String shop = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single();
        core.notifyMarketer(acc, t, "APPLICATION", switch (decision) { case "APPROVE", "UNBLOCK" -> shop + ": you are approved. Your link is ready."; case "REJECT" -> shop + ": your application was not accepted."; default -> shop + ": your access was stopped."; });
        return partner(t, partnerId);
    }

    // ---- offers ------------------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> offers(UUID t) {
        return Rows.camel(jdbc.sql("SELECT o.id, o.title, o.percent, o.starts_at, o.ends_at, o.active, o.partner_id, p.name AS partner_name FROM affiliate.offers o LEFT JOIN affiliate.partners p ON p.id = o.partner_id WHERE o.tenant_id = :t ORDER BY o.created_at DESC LIMIT 200").param("t", t).query().listOfRows());
    }

    public Map<String, Object> addOffer(UUID t, UUID partnerId, String title, double percent, String startsAt, String endsAt) {
        double cap = jdbc.sql("SELECT max_percent FROM affiliate.settings WHERE id = 1").query(Double.class).single();
        if (title == null || title.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.title", "Write the offer's name");
        if (percent < 0 || percent > cap) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.percent", "The commission must be between 0% and " + cap + "%");
        if (partnerId != null) partner(t, partnerId);
        UUID id = jdbc.sql("INSERT INTO affiliate.offers (tenant_id, partner_id, title, percent, starts_at, ends_at) VALUES (:t, :p, :ti, :pc, coalesce(CAST(:s AS timestamptz), now()), CAST(:e AS timestamptz)) RETURNING id")
                .param("t", t).param("p", partnerId).param("ti", title.strip()).param("pc", percent).param("s", blank(startsAt)).param("e", blank(endsAt)).query(UUID.class).single();
        audit.log("SHOP", null, t, partnerId, "OFFER_ADDED", title + " " + percent + "%");
        if (partnerId != null) core.notifyMarketer(jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :p").param("p", partnerId).query(UUID.class).optional().orElse(null), t, "OFFER", "New offer for you: " + title + " (" + percent + "%)");
        return offers(t).stream().filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
    }

    public void setOfferActive(UUID t, UUID offerId, boolean active) {
        if (jdbc.sql("UPDATE affiliate.offers SET active = :a WHERE id = :i AND tenant_id = :t").param("a", active).param("i", offerId).param("t", t).update() == 0) throw new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Offer not found");
        audit.log("SHOP", null, t, null, active ? "OFFER_ON" : "OFFER_OFF", offerId.toString());
    }

    // ---- payout requests ---------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> payouts(UUID t, String status) {
        return Rows.camel(jdbc.sql("SELECT r.id, r.amount_minor, r.status, r.note, r.evidence, r.decision_note, r.requested_at, r.decided_at, p.id AS partner_id, p.name, a.payout_note FROM affiliate.payout_requests r JOIN affiliate.partners p ON p.id = r.partner_id LEFT JOIN affiliate.accounts a ON a.id = p.account_id WHERE r.tenant_id = :t AND (CAST(:s AS text) IS NULL OR r.status = :s) ORDER BY r.requested_at DESC LIMIT 200")
                .param("t", t).param("s", blank(status)).query().listOfRows());
    }

    /** The shop says it paid this request (with its proof): the commissions in it become PAID. This records a payment the shop made; the platform moves nothing. */
    @Transactional
    public void markRequestPaid(UUID t, UUID requestId, String evidence) {
        var r = jdbc.sql("SELECT partner_id FROM affiliate.payout_requests WHERE id = :i AND tenant_id = :t AND status = 'REQUESTED' FOR UPDATE").param("i", requestId).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "affiliate.notOpen", "This request is not open"));
        if (evidence == null || evidence.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.evidence", "Write how you paid (a reference, a receipt link or a short note)");
        for (var c : jdbc.sql("SELECT id, amount_minor FROM affiliate.commissions WHERE payout_request_id = :r AND status = 'APPROVED' FOR UPDATE").param("r", requestId).query().listOfRows())
            core.markPaid((UUID) c.get("id"), t, ((Number) c.get("amount_minor")).longValue(), requestId);
        jdbc.sql("UPDATE affiliate.payout_requests SET status = 'PAID', evidence = :e, decided_at = now() WHERE id = :i").param("e", evidence.strip()).param("i", requestId).update();
        UUID pid = (UUID) r.get("partner_id");
        audit.log("SHOP", null, t, pid, "PAYOUT_PAID", requestId.toString());
        core.notifyMarketer(jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :p").param("p", pid).query(UUID.class).optional().orElse(null), t, "PAYOUT", "Your payout request was paid");
    }

    @Transactional
    public void rejectRequest(UUID t, UUID requestId, String note) {
        var r = jdbc.sql("SELECT partner_id FROM affiliate.payout_requests WHERE id = :i AND tenant_id = :t AND status = 'REQUESTED' FOR UPDATE").param("i", requestId).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "affiliate.notOpen", "This request is not open"));
        jdbc.sql("UPDATE affiliate.commissions SET payout_request_id = NULL WHERE payout_request_id = :r AND status = 'APPROVED'").param("r", requestId).update();
        jdbc.sql("UPDATE affiliate.payout_requests SET status = 'REJECTED', decision_note = :n, decided_at = now() WHERE id = :i").param("n", note == null ? null : note.strip()).param("i", requestId).update();
        UUID pid = (UUID) r.get("partner_id");
        audit.log("SHOP", null, t, pid, "PAYOUT_REJECTED", note);
        core.notifyMarketer(jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :p").param("p", pid).query(UUID.class).optional().orElse(null), t, "PAYOUT", "Your payout request was declined" + (note == null || note.isBlank() ? "" : ": " + note.strip()));
    }

    // ---- reviews, chat, disputes -------------------------------------------------------------------------------------------------

    /** Only a real working relationship can be rated: at least one approved or paid commission with this marketer. */
    public static final String RELATION = "SELECT count(*) FROM affiliate.commissions WHERE tenant_id = :t AND partner_id = :p AND status IN ('APPROVED','PAID')";

    public Map<String, Object> rateMarketer(UUID t, UUID partnerId, int rating, String comment) {
        partner(t, partnerId);
        if (rating < 1 || rating > 5) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.rating", "The rating is 1 to 5");
        if (jdbc.sql(RELATION).param("t", t).param("p", partnerId).query(Long.class).single() == 0) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.noRelation", "You can rate a marketer after at least one of their orders is approved");
        jdbc.sql("INSERT INTO affiliate.reviews (tenant_id, partner_id, direction, rating, comment) VALUES (:t, :p, 'SHOP_TO_MARKETER', :r, :c) ON CONFLICT (tenant_id, partner_id, direction) DO UPDATE SET rating = :r, comment = :c, created_at = now()")
                .param("t", t).param("p", partnerId).param("r", rating).param("c", cut(comment, 500)).update();
        audit.log("SHOP", null, t, partnerId, "RATED_MARKETER", String.valueOf(rating));
        return Map.of("rating", rating);
    }

    public List<Map<String, Object>> reviews(UUID t) {
        return Rows.camel(jdbc.sql("SELECT r.id, r.direction, r.rating, r.comment, r.created_at, p.name FROM affiliate.reviews r JOIN affiliate.partners p ON p.id = r.partner_id WHERE r.tenant_id = :t ORDER BY r.created_at DESC LIMIT 200").param("t", t).query().listOfRows());
    }

    public List<Map<String, Object>> thread(UUID t, UUID partnerId) {
        partner(t, partnerId);
        jdbc.sql("UPDATE affiliate.messages SET read_at = now() WHERE tenant_id = :t AND partner_id = :p AND sender = 'MARKETER' AND read_at IS NULL").param("t", t).param("p", partnerId).update();
        return Rows.camel(jdbc.sql("SELECT id, sender, body, created_at FROM affiliate.messages WHERE tenant_id = :t AND partner_id = :p ORDER BY id DESC LIMIT 100").param("t", t).param("p", partnerId).query().listOfRows());
    }

    public void send(UUID t, UUID partnerId, String body) {
        var p = partner(t, partnerId);
        if ("BLOCKED".equals(p.get("status"))) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.blocked", "This marketer is blocked");
        String b = message(body);
        jdbc.sql("INSERT INTO affiliate.messages (tenant_id, partner_id, sender, body) VALUES (:t, :p, 'SHOP', :b)").param("t", t).param("p", partnerId).param("b", b).update();
        core.notifyMarketer(jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :p").param("p", partnerId).query(UUID.class).optional().orElse(null), t, "MESSAGE", "New message from the shop");
    }

    public List<Map<String, Object>> inbox(UUID t) {
        return Rows.camel(jdbc.sql("""
                SELECT p.id AS partner_id, p.name, (SELECT body FROM affiliate.messages m WHERE m.partner_id = p.id ORDER BY m.id DESC LIMIT 1) AS last_body,
                       (SELECT max(created_at) FROM affiliate.messages m WHERE m.partner_id = p.id) AS last_at,
                       (SELECT count(*) FROM affiliate.messages m WHERE m.partner_id = p.id AND m.sender = 'MARKETER' AND m.read_at IS NULL) AS unread
                FROM affiliate.partners p WHERE p.tenant_id = :t AND EXISTS (SELECT 1 FROM affiliate.messages m WHERE m.partner_id = p.id) ORDER BY last_at DESC
                """).param("t", t).query().listOfRows());
    }

    public List<Map<String, Object>> disputes(UUID t) {
        return Rows.camel(jdbc.sql("SELECT d.id, d.opened_by, d.reason, d.status, d.resolution, d.created_at, d.resolved_at, d.commission_id, p.name FROM affiliate.disputes d JOIN affiliate.partners p ON p.id = d.partner_id WHERE d.tenant_id = :t ORDER BY d.created_at DESC LIMIT 200").param("t", t).query().listOfRows());
    }

    public void openDispute(UUID t, UUID partnerId, UUID commissionId, String reason) {
        partner(t, partnerId);
        if (reason == null || reason.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.reason", "Write the reason");
        jdbc.sql("INSERT INTO affiliate.disputes (tenant_id, partner_id, commission_id, opened_by, reason) VALUES (:t, :p, :c, 'SHOP', :r)").param("t", t).param("p", partnerId).param("c", commissionId).param("r", cut(reason, 600)).update();
        audit.log("SHOP", null, t, partnerId, "DISPUTE_OPENED", reason);
    }

    public void resolveDispute(UUID t, UUID id, String resolution) {
        if (jdbc.sql("UPDATE affiliate.disputes SET status = 'RESOLVED', resolution = :r, resolved_at = now() WHERE id = :i AND tenant_id = :t AND status = 'OPEN'").param("r", cut(resolution, 600)).param("i", id).param("t", t).update() == 0)
            throw new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Open dispute not found");
        audit.log("SHOP", null, t, null, "DISPUTE_RESOLVED", resolution);
    }

    public Map<String, Object> badges(UUID t) {
        return Map.of("applications", jdbc.sql("SELECT count(*) FROM affiliate.partners WHERE tenant_id = :t AND status = 'PENDING' AND account_id IS NOT NULL").param("t", t).query(Long.class).single(),
                "payouts", jdbc.sql("SELECT count(*) FROM affiliate.payout_requests WHERE tenant_id = :t AND status = 'REQUESTED'").param("t", t).query(Long.class).single(),
                "messages", jdbc.sql("SELECT count(*) FROM affiliate.messages WHERE tenant_id = :t AND sender = 'MARKETER' AND read_at IS NULL").param("t", t).query(Long.class).single(),
                "disputes", jdbc.sql("SELECT count(*) FROM affiliate.disputes WHERE tenant_id = :t AND status = 'OPEN'").param("t", t).query(Long.class).single());
    }

    static String message(String body) {
        if (body == null || body.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.empty", "Write a message");
        String b = body.strip();
        if (b.length() > 1000) b = b.substring(0, 1000);
        return b;
    }

    static String cut(String s, int n) { return s == null || s.isBlank() ? null : s.strip().length() > n ? s.strip().substring(0, n) : s.strip(); }
    static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}

package com.platform.legal;

import com.platform.audit.AuditService;
import com.platform.core.auth.AccountAuthService;
import com.platform.core.auth.LoginThrottle;
import com.platform.core.auth.TokenResponse;
import com.platform.shared.BusinessException;
import com.platform.shared.FieldErrors;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The office app for a lawyer's clients. A client can sign in only when the lawyer added them with a phone and turned their access on:
 * the lawyer gets a temporary password to send them (WhatsApp), and the client sets their own at the first sign-in. A client sees only
 * their own cases (status, hearings, fees and payments - never the office's internal notes), books sessions and follows their bookings.
 */
@Service
public class LegalPortalService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> BUSY = Set.of("PENDING", "APPROVED");

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AccountAuthService accounts;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final LegalBookingService bookings;
    private final LegalProfileService profiles;

    public LegalPortalService(JdbcClient jdbc, PasswordEncoder encoder, AccountAuthService accounts, LoginThrottle throttle, AuditService audit, LegalBookingService bookings, LegalProfileService profiles) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.accounts = accounts;
        this.throttle = throttle;
        this.audit = audit;
        this.bookings = bookings;
        this.profiles = profiles;
    }

    // ---- the lawyer turns a client's access on / off -------------------------------------------------------------------------------

    /** ENABLE or RESET: a new temporary password (shown once) and the WhatsApp text to send it; DISABLE: the client is signed out and can no longer sign in. */
    @Transactional
    public Map<String, Object> setAccess(UUID tenantId, UUID actor, UUID clientId, String action) {
        var c = jdbc.sql("SELECT name, phone FROM legal.clients WHERE id = :i AND tenant_id = :t").param("i", clientId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Client not found"));
        String phone = (String) c.get("phone");
        if ("DISABLE".equals(action)) {
            jdbc.sql("UPDATE legal.clients SET portal_enabled = FALSE, updated_at = now() WHERE id = :i").param("i", clientId).update();
            revoke(clientId);
            audit.record(actor, tenantId, "LEGAL_CLIENT_APP_OFF", "legal_client", clientId, null);
            return Map.of("portalEnabled", false);
        }
        if (!Set.of("ENABLE", "RESET").contains(action)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown action").field("action");
        if (phone == null || phone.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Add the client's mobile number first: they sign in with it").field("phone");
        String password = String.format("%08d", RANDOM.nextInt(100_000_000));
        jdbc.sql("UPDATE legal.clients SET portal_enabled = TRUE, portal_password_hash = :h, portal_must_change = TRUE, updated_at = now() WHERE id = :i")
                .param("h", encoder.encode(password)).param("i", clientId).update();
        revoke(clientId);
        audit.record(actor, tenantId, "LEGAL_CLIENT_APP_" + action, "legal_client", clientId, null);
        var office = jdbc.sql("SELECT p.display_name, p.title, p.client_app_url, t.slug FROM legal.profiles p JOIN core.tenants t ON t.id = p.tenant_id WHERE p.tenant_id = :t")
                .param("t", tenantId).query().singleRow();
        String officeName = (blank(office.get("title")) ? "" : office.get("title") + " ") + office.get("display_name");
        String local = localPhone(phone);
        StringBuilder msg = new StringBuilder("أهلًا ").append(c.get("name")).append("،\n")
                .append("يمكنك الآن متابعة قضاياك وجلساتها وحجز مواعيدك من تطبيق ").append(officeName).append(".\n");
        if (!blank(office.get("client_app_url"))) msg.append("حمّل التطبيق: ").append(office.get("client_app_url")).append("\n");
        msg.append("\nرقم الدخول: ").append(local).append("\nكلمة المرور المؤقتة: ").append(password).append("\n(ستختار كلمة مرور جديدة عند أول دخول)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("portalEnabled", true);
        out.put("phone", local);
        out.put("password", password);
        out.put("message", msg.toString());
        out.put("whatsapp", phone.replaceAll("\\D", ""));
        return out;
    }

    private void revoke(UUID clientId) {
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND principal_type = 'LAWCLIENT' AND revoked_at IS NULL").param("u", clientId).update();
    }

    // ---- the client signs in ----------------------------------------------------------------------------------------------------------

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(UUID tenantId, String rawPhone, String password, String ip, String ua) {
        String phone = PhoneNormalizer.tryNormalize(rawPhone);
        var fe = new FieldErrors();
        fe.when(phone == null, "phone", "Write the mobile number the office has");
        fe.when(password == null || password.isBlank(), "password", "Write your password");
        fe.throwIfAny();
        throttle.check("lawclient-login|" + tenantId + "|" + phone, 8, Duration.ofMinutes(15));
        throttle.check("lawclient-login-ip|" + ip, 30, Duration.ofMinutes(15));
        var c = jdbc.sql("SELECT id, portal_password_hash FROM legal.clients WHERE tenant_id = :t AND phone = :p AND portal_enabled").param("t", tenantId).param("p", phone)
                .query().listOfRows().stream().findFirst().orElse(null);
        if (c == null || c.get("portal_password_hash") == null || !encoder.matches(password, (String) c.get("portal_password_hash")))
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Wrong number or password. Ask the office for your sign-in details");
        UUID id = (UUID) c.get("id");
        jdbc.sql("UPDATE legal.clients SET portal_last_login_at = now() WHERE id = :i").param("i", id).update();
        return accounts.issueLegalClientTokens(id, ip, ua);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse changePassword(UUID clientId, String current, String next, String ip, String ua) {
        throttle.check("lawclient-chgpw|" + clientId, 5, Duration.ofMinutes(10));
        var fe = new FieldErrors();
        fe.when(next == null || next.length() < 8, "newPassword", "Password must be at least 8 characters");
        fe.when(next != null && next.length() > 128, "newPassword", "Password is too long");
        fe.throwIfAny();
        String hash = jdbc.sql("SELECT portal_password_hash FROM legal.clients WHERE id = :i AND portal_enabled").param("i", clientId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.unauthorized("UNAUTHENTICATED", "Unknown account"));
        if (!encoder.matches(current == null ? "" : current, hash)) throw BusinessException.unauthorized("INVALID_CREDENTIALS", "Current password is wrong").field("currentPassword");
        if (encoder.matches(next, hash)) throw BusinessException.badRequest("PASSWORD_UNCHANGED", "Choose a password different from the temporary one").field("newPassword");
        jdbc.sql("UPDATE legal.clients SET portal_password_hash = :h, portal_must_change = FALSE, updated_at = now() WHERE id = :i").param("h", encoder.encode(next)).param("i", clientId).update();
        revoke(clientId);
        return accounts.issueLegalClientTokens(clientId, ip, ua);
    }

    // ---- what the client sees ---------------------------------------------------------------------------------------------------------

    private Map<String, Object> me(UUID tenantId, UUID clientId) {
        return jdbc.sql("SELECT id, name, phone, portal_must_change FROM legal.clients WHERE id = :i AND tenant_id = :t AND portal_enabled").param("i", clientId).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.unauthorized("UNAUTHENTICATED", "Your access was turned off by the office"));
    }

    private static final String CASE_COLS = """
            k.id, k.number, k.title, k.case_type, k.court, k.circuit, k.court_case_no, k.opponent, k.client_role, k.status, k.outcome, k.opened_on, k.closed_on,
            k.agreed_fee_minor + (SELECT coalesce(sum(a.fee_minor), 0) FROM legal.case_actions a WHERE a.case_id = k.id) AS billed_minor,
            (SELECT coalesce(sum(p.amount_minor), 0) FROM legal.payments p WHERE p.case_id = k.id) AS paid_minor,
            (SELECT min(s.starts_at) FROM legal.case_steps s WHERE s.case_id = k.id AND NOT s.done AND s.starts_at > now()) AS next_step_at
            """;

    /** Home: who they are, their cases, and what is coming next (their hearings and approved sessions). */
    public Map<String, Object> home(UUID tenantId, UUID clientId) {
        var me = me(tenantId, clientId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", me.get("name"));
        out.put("phone", localPhone((String) me.get("phone")));
        out.put("mustChangePassword", me.get("portal_must_change"));
        out.put("cases", cases(tenantId, clientId));
        out.put("upcoming", Rows.camel(jdbc.sql("""
                SELECT * FROM (
                  SELECT 'STEP' AS kind, st.kind AS step_kind, st.id, st.starts_at, st.description AS title, coalesce(nullif(st.location, ''), k.court) AS place, k.id AS case_id, k.number AS case_number, NULL AS status
                  FROM legal.case_steps st JOIN legal.cases k ON k.id = st.case_id JOIN legal.case_clients cc ON cc.case_id = k.id
                  WHERE cc.client_id = :c AND k.tenant_id = :t AND NOT st.done AND st.starts_at > now()
                  UNION ALL
                  SELECT 'BOOKING', NULL, b.id, b.starts_at, coalesce(s.name, 'استشارة'), '', b.case_id, NULL, b.status
                  FROM legal.bookings b LEFT JOIN legal.services s ON s.id = b.service_id
                  WHERE b.tenant_id = :t AND (b.client_id = :c OR b.guest_phone = :p) AND b.status IN ('PENDING','APPROVED') AND b.starts_at > now()
                ) x ORDER BY starts_at LIMIT 8
                """).param("c", clientId).param("t", tenantId).param("p", me.get("phone")).query().listOfRows()));
        return out;
    }

    public List<Map<String, Object>> cases(UUID tenantId, UUID clientId) {
        return Rows.camel(jdbc.sql("SELECT " + CASE_COLS + " FROM legal.cases k JOIN legal.case_clients cc ON cc.case_id = k.id WHERE cc.client_id = :c AND k.tenant_id = :t ORDER BY (k.status = 'CLOSED'), k.opened_on DESC")
                .param("c", clientId).param("t", tenantId).query().listOfRows());
    }

    /** One of their cases: its hearings and dates (past and coming), fees and payments. The office's notes and documents stay internal. */
    public Map<String, Object> caseDetail(UUID tenantId, UUID clientId, UUID caseId) {
        me(tenantId, clientId);
        var k = jdbc.sql("SELECT " + CASE_COLS + " FROM legal.cases k JOIN legal.case_clients cc ON cc.case_id = k.id WHERE cc.client_id = :c AND k.tenant_id = :t AND k.id = :k")
                .param("c", clientId).param("t", tenantId).param("k", caseId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Case not found"));
        Map<String, Object> out = Rows.camel(k);
        out.put("steps", Rows.camel(jdbc.sql("SELECT id, kind, starts_at, description, location, done, result FROM legal.case_steps WHERE case_id = :k ORDER BY starts_at DESC")
                .param("k", caseId).query().listOfRows()));
        out.put("payments", Rows.camel(jdbc.sql("SELECT paid_on, amount_minor, method FROM legal.payments WHERE case_id = :k ORDER BY paid_on DESC").param("k", caseId).query().listOfRows()));
        long billed = ((Number) k.get("billed_minor")).longValue(), paid = ((Number) k.get("paid_minor")).longValue();
        out.put("balanceMinor", Math.max(0, billed - paid));
        return out;
    }

    public List<Map<String, Object>> myBookings(UUID tenantId, UUID clientId) {
        var me = me(tenantId, clientId);
        return Rows.camel(jdbc.sql("""
                SELECT b.id, b.code, b.starts_at, b.ends_at, b.status, b.topic, b.decision_note, b.case_id, s.name AS service_name, s.price_minor, s.mode
                FROM legal.bookings b LEFT JOIN legal.services s ON s.id = b.service_id
                WHERE b.tenant_id = :t AND (b.client_id = :c OR b.guest_phone = :p) ORDER BY (b.starts_at < now()), b.starts_at LIMIT 60
                """).param("t", tenantId).param("c", clientId).param("p", me.get("phone")).query().listOfRows());
    }

    public record BookReq(UUID serviceId, java.time.Instant startsAt, String topic, UUID caseId) {}

    /** A session asked from the app: same rules as the website, already tied to the client (and to one of their cases if they chose it). */
    @Transactional
    public Map<String, Object> book(UUID tenantId, UUID clientId, BookReq r) {
        var me = me(tenantId, clientId);
        if (r.caseId() != null && jdbc.sql("SELECT count(*) FROM legal.case_clients cc JOIN legal.cases k ON k.id = cc.case_id WHERE cc.client_id = :c AND cc.case_id = :k AND k.tenant_id = :t")
                .param("c", clientId).param("k", r.caseId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "Choose one of your cases").field("caseId");
        Map<String, Object> made = bookings.request(tenantId, new LegalBookingService.BookingReq(r.serviceId(), r.startsAt(), (String) me.get("name"), (String) me.get("phone"), r.topic()));
        jdbc.sql("UPDATE legal.bookings SET client_id = :c, case_id = coalesce(:k, case_id), source = 'APP' WHERE tenant_id = :t AND upper(code) = upper(:code)")
                .param("c", clientId).param("k", r.caseId()).param("t", tenantId).param("code", made.get("code")).update();
        return made;
    }

    @Transactional
    public Map<String, Object> cancel(UUID tenantId, UUID clientId, UUID bookingId) {
        var me = me(tenantId, clientId);
        var b = jdbc.sql("SELECT code, guest_phone, status FROM legal.bookings WHERE id = :i AND tenant_id = :t AND (client_id = :c OR guest_phone = :p)")
                .param("i", bookingId).param("t", tenantId).param("c", clientId).param("p", me.get("phone")).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        if (!BUSY.contains((String) b.get("status"))) throw BusinessException.conflict("BOOKING_CLOSED", "This booking can no longer be cancelled");
        return bookings.cancelByGuest(tenantId, (String) b.get("code"), (String) b.get("guest_phone"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------------

    /** +201012345678 -> 01012345678 (how Egyptians write their number); other countries stay international. */
    static String localPhone(String p) {
        if (p == null) return "";
        return p.startsWith("+20") ? "0" + p.substring(3) : p;
    }

    private static boolean blank(Object o) { return o == null || o.toString().isBlank(); }
}

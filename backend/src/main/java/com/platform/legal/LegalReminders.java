package com.platform.legal;

import com.platform.notifications.NotificationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The lawyer's phone and bell: a new booking request, a cancelled one, one hour before every session / hearing / note,
 * an early reminder N days before a case step, and an evening digest of tomorrow's agenda.
 */
@Component
public class LegalReminders {
    private static final Logger log = LoggerFactory.getLogger(LegalReminders.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d/M", new java.util.Locale("ar"));

    private final JdbcClient jdbc;
    private final NotificationService notifications;

    public LegalReminders(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBooking(LegalEvents.BookingRequested e) {
        safely(() -> {
            String when = when(e.tenantId(), e.startsAt());
            String title = e.autoApproved() ? "حجز جديد مؤكد" : "طلب حجز جديد";
            String body = e.name() + " — " + when + (e.autoApproved() ? "" : ". اضغط للموافقة أو الرفض.");
            send(e.tenantId(), "legal.booking.read", "LEGAL_BOOKING_REQUESTED", title, body, "{\"type\":\"LEGAL_BOOKING\",\"bookingId\":\"" + e.bookingId() + "\"}");
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancel(LegalEvents.BookingCancelled e) {
        safely(() -> send(e.tenantId(), "legal.booking.read", "LEGAL_BOOKING_CANCELLED", "تم إلغاء حجز", e.name() + " ألغى موعد " + when(e.tenantId(), e.startsAt()),
                "{\"type\":\"LEGAL_BOOKING\",\"bookingId\":\"" + e.bookingId() + "\"}"));
    }

    /** Every 5 minutes: one hour before each approved session, case step and timed note; N days before each step. */
    @Scheduled(fixedDelayString = "${app.legal.reminder-interval:PT5M}", initialDelayString = "PT2M")
    @Transactional
    public void remind() {
        String live = "AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = %s.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))";
        for (var b : jdbc.sql("""
                UPDATE legal.bookings SET reminder_sent_at = now() WHERE status = 'APPROVED' AND reminder_sent_at IS NULL AND starts_at > now() AND starts_at <= now() + interval '65 minutes'
                """ + live.formatted("legal.bookings") + " RETURNING id, tenant_id, guest_name, guest_phone, starts_at").query().listOfRows()) {
            UUID t = (UUID) b.get("tenant_id");
            safely(() -> send(t, "legal.schedule.read", "LEGAL_SESSION_SOON", "جلسة بعد ساعة", b.get("guest_name") + " — " + when(t, ts(b.get("starts_at"))) + " (" + b.get("guest_phone") + ")",
                    "{\"type\":\"LEGAL_BOOKING\",\"bookingId\":\"" + b.get("id") + "\"}"));
        }
        for (var s : jdbc.sql("""
                UPDATE legal.case_steps st SET reminder_sent_at = now() FROM legal.cases k WHERE k.id = st.case_id AND NOT st.done AND st.reminder_sent_at IS NULL
                  AND st.starts_at > now() AND st.starts_at <= now() + interval '65 minutes'
                """ + live.formatted("st") + " RETURNING st.id, st.tenant_id, st.case_id, st.kind, st.description, st.location, st.starts_at, k.number, k.title").query().listOfRows()) {
            UUID t = (UUID) s.get("tenant_id");
            safely(() -> send(t, "legal.schedule.read", "LEGAL_STEP_SOON", kindAr((String) s.get("kind")) + " بعد ساعة — قضية " + s.get("number"),
                    s.get("description") + " • " + when(t, ts(s.get("starts_at"))) + (blank(s.get("location")) ? "" : " • " + s.get("location")),
                    "{\"type\":\"LEGAL_CASE\",\"caseId\":\"" + s.get("case_id") + "\",\"stepId\":\"" + s.get("id") + "\"}"));
        }
        for (var s : jdbc.sql("""
                UPDATE legal.case_steps st SET early_reminder_sent_at = now() FROM legal.cases k, legal.profiles p
                WHERE k.id = st.case_id AND p.tenant_id = st.tenant_id AND p.reminder_days_before > 0 AND NOT st.done AND st.early_reminder_sent_at IS NULL
                  AND st.starts_at > now() + interval '2 hours' AND st.starts_at <= now() + make_interval(days => p.reminder_days_before)
                """ + live.formatted("st") + " RETURNING st.id, st.tenant_id, st.case_id, st.kind, st.description, st.starts_at, k.number").query().listOfRows()) {
            UUID t = (UUID) s.get("tenant_id");
            safely(() -> send(t, "legal.schedule.read", "LEGAL_STEP_UPCOMING", "تذكير: " + kindAr((String) s.get("kind")) + " قادمة — قضية " + s.get("number"),
                    s.get("description") + " • " + when(t, ts(s.get("starts_at"))), "{\"type\":\"LEGAL_CASE\",\"caseId\":\"" + s.get("case_id") + "\",\"stepId\":\"" + s.get("id") + "\"}"));
        }
        for (var n : jdbc.sql("""
                UPDATE legal.calendar_notes SET reminder_sent_at = now() WHERE NOT all_day AND reminder_sent_at IS NULL AND starts_at > now() AND starts_at <= now() + interval '65 minutes'
                """ + live.formatted("legal.calendar_notes") + " RETURNING id, tenant_id, title, starts_at, case_id").query().listOfRows()) {
            UUID t = (UUID) n.get("tenant_id");
            safely(() -> send(t, "legal.schedule.read", "LEGAL_NOTE_SOON", "تذكير بعد ساعة", n.get("title") + " • " + when(t, ts(n.get("starts_at"))),
                    n.get("case_id") == null ? "{\"type\":\"LEGAL_SCHEDULE\"}" : "{\"type\":\"LEGAL_CASE\",\"caseId\":\"" + n.get("case_id") + "\"}"));
        }
        notifications.dispatchSoon();
    }

    /** Hourly: after 8 pm in the office's time zone, tomorrow's agenda in one message (once a day, only when there is something). */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void dailyDigest() {
        for (var p : jdbc.sql("""
                SELECT p.tenant_id, t.timezone FROM legal.profiles p JOIN core.tenants t ON t.id = p.tenant_id
                WHERE p.daily_digest AND t.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')
                """).query().listOfRows()) {
            UUID t = (UUID) p.get("tenant_id");
            ZoneId tz = zone((String) p.get("timezone"));
            ZonedDateTime now = ZonedDateTime.now(tz);
            if (now.getHour() < 20) continue;
            safely(() -> digest(t, tz, now.toLocalDate()));
        }
    }

    void digest(UUID tenantId, ZoneId tz, LocalDate today) {
        if (jdbc.sql("INSERT INTO legal.digest_sent (tenant_id, day) VALUES (:t, :d) ON CONFLICT DO NOTHING").param("t", tenantId).param("d", today).update() == 0) return;
        LocalDate tomorrow = today.plusDays(1);
        java.sql.Timestamp s = java.sql.Timestamp.from(tomorrow.atStartOfDay(tz).toInstant()), e = java.sql.Timestamp.from(tomorrow.plusDays(1).atStartOfDay(tz).toInstant());
        List<Map<String, Object>> steps = jdbc.sql("SELECT st.kind, st.starts_at, st.description, k.number FROM legal.case_steps st JOIN legal.cases k ON k.id = st.case_id WHERE st.tenant_id = :t AND NOT st.done AND st.starts_at >= :s AND st.starts_at < :e ORDER BY st.starts_at")
                .param("t", tenantId).param("s", s).param("e", e).query().listOfRows();
        long sessions = jdbc.sql("SELECT count(*) FROM legal.bookings WHERE tenant_id = :t AND status = 'APPROVED' AND starts_at >= :s AND starts_at < :e").param("t", tenantId).param("s", s).param("e", e).query(Long.class).single();
        if (steps.isEmpty() && sessions == 0) return;
        StringBuilder body = new StringBuilder();
        long hearings = steps.stream().filter(x -> "HEARING".equals(x.get("kind"))).count();
        if (hearings > 0) body.append(hearings).append(" جلسة محكمة");
        if (steps.size() > hearings) body.append(body.length() > 0 ? " • " : "").append(steps.size() - hearings).append(" مواعيد قضايا");
        if (sessions > 0) body.append(body.length() > 0 ? " • " : "").append(sessions).append(" استشارة");
        if (!steps.isEmpty()) {
            var first = steps.get(0);
            body.append("\nأولها ").append(TIME.format(ts(first.get("starts_at")).atZone(tz))).append(": ").append(first.get("description")).append(" (قضية ").append(first.get("number")).append(")");
        }
        send(tenantId, "legal.schedule.read", "LEGAL_DAILY_DIGEST", "أجندة الغد — " + DAY.format(tomorrow), body.toString(), "{\"type\":\"LEGAL_SCHEDULE\",\"date\":\"" + tomorrow + "\"}");
        notifications.dispatchSoon();
    }

    private void send(UUID tenantId, String permission, String type, String title, String body, String payload) {
        for (UUID u : jdbc.sql("""
                SELECT DISTINCT m.user_id FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id
                JOIN core.role_permissions rp ON rp.role_id = mr.role_id JOIN core.permissions p ON p.id = rp.permission_id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND p.code = :p
                """).param("t", tenantId).param("p", permission).query(UUID.class).list()) {
            notifications.notify(u, tenantId, type, title, body, payload, false);
            notifications.push(u, tenantId, title, body, payload);
        }
    }

    private String when(UUID tenantId, Instant at) {
        ZoneId tz = zone(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo"));
        ZonedDateTime z = at.atZone(tz);
        LocalDate today = LocalDate.now(tz);
        String day = z.toLocalDate().equals(today) ? "اليوم" : z.toLocalDate().equals(today.plusDays(1)) ? "غدًا" : DAY.format(z);
        return day + " " + TIME.format(z).replace("AM", "ص").replace("PM", "م");
    }

    static String kindAr(String k) {
        return switch (k == null ? "" : k) {
            case "HEARING" -> "جلسة";
            case "DEADLINE" -> "موعد نهائي";
            case "FILING" -> "إيداع";
            case "MEETING" -> "اجتماع";
            default -> "موعد";
        };
    }

    private static ZoneId zone(String z) { try { return ZoneId.of(z); } catch (RuntimeException e) { return ZoneId.of("Africa/Cairo"); } }

    private static Instant ts(Object o) { return o instanceof java.sql.Timestamp t ? t.toInstant() : (Instant) o; }

    private static boolean blank(Object o) { return o == null || o.toString().isBlank(); }

    private void safely(Runnable r) {
        try { r.run(); } catch (Exception ex) { log.warn("Legal notification failed: {}", ex.getMessage()); }
    }
}

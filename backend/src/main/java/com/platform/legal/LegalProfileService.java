package com.platform.legal;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.FieldErrors;
import com.platform.shared.Rows;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The lawyer's public profile and website, booking rules, services and working hours. */
@Service
public class LegalProfileService {
    static final Set<String> JSON_FIELDS = Set.of("practiceAreas", "languages", "education", "achievements", "testimonials", "faq", "social", "siteLayout");
    private static final Map<String, String> TEXT = new LinkedHashMap<>();
    static {
        TEXT.put("displayName", "display_name"); TEXT.put("title", "title"); TEXT.put("headline", "headline"); TEXT.put("bio", "bio");
        TEXT.put("photoUrl", "photo_url"); TEXT.put("coverUrl", "cover_url"); TEXT.put("logoUrl", "logo_url");
        TEXT.put("barNumber", "bar_number"); TEXT.put("barLevel", "bar_level");
        TEXT.put("officeAddress", "office_address"); TEXT.put("officeCity", "office_city"); TEXT.put("mapUrl", "map_url");
        TEXT.put("phone", "phone"); TEXT.put("whatsapp", "whatsapp"); TEXT.put("email", "email"); TEXT.put("template", "template");
    }
    private static final Map<String, String> NUM = Map.of("yearsExperience", "years_experience", "slotMinutes", "slot_minutes", "bufferMinutes", "buffer_minutes",
            "minNoticeHours", "min_notice_hours", "maxDaysAhead", "max_days_ahead", "maxPerDay", "max_per_day", "reminderDaysBefore", "reminder_days_before");
    private static final Map<String, String> BOOL = Map.of("autoApprove", "auto_approve", "bookingEnabled", "booking_enabled", "dailyDigest", "daily_digest");
    private static final Map<String, String> JSON_COL = Map.of("practiceAreas", "practice_areas", "languages", "languages", "education", "education", "achievements", "achievements",
            "testimonials", "testimonials", "faq", "faq", "social", "social", "siteLayout", "site_layout");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public LegalProfileService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public ZoneId zone(UUID tenantId) {
        String z = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo");
        try { return ZoneId.of(z); } catch (RuntimeException e) { return ZoneId.of("Africa/Cairo"); }
    }

    public Map<String, Object> profile(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT p.*, p.practice_areas::text AS pa, p.languages::text AS la, p.education::text AS ed, p.achievements::text AS ac, p.testimonials::text AS te, p.faq::text AS fq,
                       p.social::text AS so, p.site_layout::text AS sl, b.primary_color, b.secondary_color, b.font_family
                FROM legal.profiles p LEFT JOIN core.branding b ON b.tenant_id = p.tenant_id WHERE p.tenant_id = :t
                """).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "No lawyer profile"));
        Map<String, Object> out = new LinkedHashMap<>();
        TEXT.forEach((k, col) -> out.put(k, r.get(col)));
        NUM.forEach((k, col) -> out.put(k, r.get(col)));
        BOOL.forEach((k, col) -> out.put(k, r.get(col)));
        out.put("practiceAreas", Rows.jsonList(r.get("pa")));
        out.put("languages", Rows.jsonList(r.get("la")));
        out.put("education", Rows.jsonList(r.get("ed")));
        out.put("achievements", Rows.jsonList(r.get("ac")));
        out.put("testimonials", Rows.jsonList(r.get("te")));
        out.put("faq", Rows.jsonList(r.get("fq")));
        out.put("social", Rows.json(r.get("so")));
        out.put("siteLayout", r.get("sl") == null ? null : Rows.jsonList(r.get("sl")));
        out.put("primaryColor", r.get("primary_color"));
        out.put("secondaryColor", r.get("secondary_color"));
        return out;
    }

    /** What visitors see: the profile without internal booking numbers, plus services, working days and the booking switch. */
    public Map<String, Object> publicProfile(UUID tenantId) {
        Map<String, Object> p = new LinkedHashMap<>(profile(tenantId));
        for (String k : List.of("bufferMinutes", "minNoticeHours", "maxPerDay", "autoApprove", "reminderDaysBefore", "dailyDigest")) p.remove(k);
        p.put("services", services(tenantId, true));
        p.put("hours", hours(tenantId));
        return p;
    }

    @Transactional
    public Map<String, Object> update(UUID tenantId, UUID actor, Map<String, Object> f) {
        var fe = new FieldErrors();
        if (f.containsKey("displayName")) fe.when(!(f.get("displayName") instanceof String s) || s.isBlank(), "displayName", "Write your name as clients should see it");
        if (f.get("template") instanceof String t) fe.when(!Set.of("classic", "modern", "heritage").contains(t), "template", "Choose one of the designs");
        if (f.get("email") instanceof String e && !e.isBlank()) fe.when(!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"), "email", "This email does not look right");
        if (f.get("mapUrl") instanceof String m && !m.isBlank()) fe.when(!m.startsWith("https://"), "mapUrl", "Paste a map link that starts with https://");
        for (String k : List.of("photoUrl", "coverUrl", "logoUrl")) if (f.get(k) instanceof String u && !u.isBlank()) fe.when(!(u.startsWith("https://") || u.startsWith("/")), k, "Upload the picture again");
        if (f.get("slotMinutes") instanceof Number n) fe.when(n.intValue() < 10 || n.intValue() > 240, "slotMinutes", "Use 10 to 240 minutes");
        if (f.get("bufferMinutes") instanceof Number n) fe.when(n.intValue() < 0 || n.intValue() > 120, "bufferMinutes", "Use 0 to 120 minutes");
        if (f.get("minNoticeHours") instanceof Number n) fe.when(n.intValue() < 0 || n.intValue() > 336, "minNoticeHours", "Use 0 to 336 hours");
        if (f.get("maxDaysAhead") instanceof Number n) fe.when(n.intValue() < 1 || n.intValue() > 365, "maxDaysAhead", "Use 1 to 365 days");
        if (f.get("yearsExperience") instanceof Number n) fe.when(n.intValue() < 0 || n.intValue() > 70, "yearsExperience", "Use 0 to 70 years");
        if (f.get("reminderDaysBefore") instanceof Number n) fe.when(n.intValue() < 0 || n.intValue() > 30, "reminderDaysBefore", "Use 0 to 30 days");
        if (f.get("social") instanceof Map<?, ?> so) for (var e : so.entrySet())
            if (e.getValue() instanceof String u && !u.isBlank()) fe.when(!u.startsWith("https://"), "social." + e.getKey(), "Links must start with https://");
        fe.throwIfAny();
        List<String> sets = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        TEXT.forEach((k, col) -> { if (f.containsKey(k)) { sets.add(col + " = :" + k); params.put(k, f.get(k) instanceof String s ? s.trim() : null); } });
        NUM.forEach((k, col) -> { if (f.containsKey(k)) { sets.add(col + " = :" + k); params.put(k, f.get(k) instanceof Number n ? n.intValue() : null); } });
        BOOL.forEach((k, col) -> { if (f.get(k) instanceof Boolean b) { sets.add(col + " = :" + k); params.put(k, b); } });
        JSON_COL.forEach((k, col) -> { if (f.containsKey(k)) { sets.add(col + " = CAST(:" + k + " AS jsonb)"); params.put(k, f.get(k) == null ? ("siteLayout".equals(k) ? null : "social".equals(k) ? "{}" : "[]") : Json.write(f.get(k))); } });
        // NOT NULL text columns: an empty value is '' (pictures may be cleared to NULL)
        for (String k : TEXT.keySet()) if (params.containsKey(k) && params.get(k) == null && !Set.of("photoUrl", "coverUrl", "logoUrl").contains(k)) params.put(k, "");
        for (String k : List.of("yearsExperience", "slotMinutes", "bufferMinutes", "minNoticeHours", "maxDaysAhead", "reminderDaysBefore")) if (params.containsKey(k) && params.get(k) == null) { sets.removeIf(s -> s.endsWith(":" + k)); params.remove(k); }
        if (!sets.isEmpty()) {
            var st = jdbc.sql("UPDATE legal.profiles SET " + String.join(", ", sets) + ", updated_at = now() WHERE tenant_id = :t").param("t", tenantId);
            for (var e : params.entrySet()) st = st.param(e.getKey(), e.getValue());
            st.update();
        }
        if (f.containsKey("displayName")) jdbc.sql("UPDATE core.tenants SET name = :n WHERE id = :t").param("n", ((String) f.get("displayName")).trim()).param("t", tenantId).update();
        audit.record(actor, tenantId, "LAWYER_PROFILE_UPDATED", "tenant", tenantId, null);
        return profile(tenantId);
    }

    // ---- services ----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> services(UUID tenantId, boolean activeOnly) {
        return Rows.camel(jdbc.sql("SELECT id, name, description, duration_minutes, price_minor, mode, is_active, sort_order FROM legal.services WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY sort_order, created_at")
                .param("t", tenantId).param("a", activeOnly).query().listOfRows());
    }

    public record ServiceReq(String name, String description, Integer durationMinutes, Long priceMinor, String mode, Boolean active, Integer sortOrder) {}

    private void checkService(ServiceReq r) {
        var fe = new FieldErrors();
        fe.when(r.name() == null || r.name().isBlank(), "name", "Name the service");
        fe.when(r.durationMinutes() != null && (r.durationMinutes() < 10 || r.durationMinutes() > 480), "durationMinutes", "Use 10 to 480 minutes");
        fe.when(r.priceMinor() != null && r.priceMinor() < 0, "priceMinor", "The price cannot be negative");
        fe.when(r.mode() != null && !Set.of("OFFICE", "ONLINE", "PHONE").contains(r.mode()), "mode", "Choose office, online or phone");
        fe.throwIfAny();
    }

    @Transactional
    public Map<String, Object> createService(UUID tenantId, UUID actor, ServiceReq r) {
        checkService(r);
        UUID id = jdbc.sql("""
                INSERT INTO legal.services (tenant_id, name, description, duration_minutes, price_minor, mode, is_active, sort_order)
                VALUES (:t, :n, :d, :du, :p, :m, :a, coalesce(:s, (SELECT coalesce(max(sort_order), -1) + 1 FROM legal.services WHERE tenant_id = :t))) RETURNING id
                """).param("t", tenantId).param("n", r.name().trim()).param("d", r.description() == null ? "" : r.description().trim()).param("du", r.durationMinutes() == null ? 30 : r.durationMinutes())
                .param("p", r.priceMinor()).param("m", r.mode() == null ? "OFFICE" : r.mode()).param("a", r.active() == null || r.active()).param("s", r.sortOrder()).query(UUID.class).single();
        audit.record(actor, tenantId, "LEGAL_SERVICE_CREATED", "legal_service", id, null);
        return services(tenantId, false).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> updateService(UUID tenantId, UUID actor, UUID id, ServiceReq r) {
        checkService(r);
        int n = jdbc.sql("""
                UPDATE legal.services SET name = :n, description = :d, duration_minutes = :du, price_minor = :p, mode = :m, is_active = :a, sort_order = coalesce(:s, sort_order)
                WHERE id = :i AND tenant_id = :t
                """).param("n", r.name().trim()).param("d", r.description() == null ? "" : r.description().trim()).param("du", r.durationMinutes() == null ? 30 : r.durationMinutes())
                .param("p", r.priceMinor()).param("m", r.mode() == null ? "OFFICE" : r.mode()).param("a", r.active() == null || r.active()).param("s", r.sortOrder()).param("i", id).param("t", tenantId).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Service not found");
        audit.record(actor, tenantId, "LEGAL_SERVICE_UPDATED", "legal_service", id, null);
        return services(tenantId, false).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteService(UUID tenantId, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM legal.services WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Service not found");
        audit.record(actor, tenantId, "LEGAL_SERVICE_DELETED", "legal_service", id, null);
    }

    // ---- hours -------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> hours(UUID tenantId) {
        return jdbc.sql("SELECT weekday, to_char(start_time, 'HH24:MI') AS s, to_char(end_time, 'HH24:MI') AS e FROM legal.working_hours WHERE tenant_id = :t ORDER BY weekday, start_time")
                .param("t", tenantId).query((rs, i) -> Map.<String, Object>of("weekday", rs.getInt(1), "start", rs.getString(2), "end", rs.getString(3))).list();
    }

    public record HourReq(int weekday, String start, String end) {}

    /** Replaces the whole week (several windows per day are fine, e.g. 10-14 and 18-21). */
    @Transactional
    public List<Map<String, Object>> setHours(UUID tenantId, UUID actor, List<HourReq> hours) {
        var fe = new FieldErrors();
        List<HourReq> list = hours == null ? List.of() : hours;
        for (int i = 0; i < list.size(); i++) {
            HourReq h = list.get(i);
            try {
                if (h.weekday() < 1 || h.weekday() > 7) fe.add("hours[" + i + "].weekday", "Choose a day");
                else if (!LocalTime.parse(h.end()).isAfter(LocalTime.parse(h.start()))) fe.add("hours[" + i + "].end", "The end must be after the start");
            } catch (RuntimeException e) { fe.add("hours[" + i + "].start", "Use a time like 10:00"); }
        }
        for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
            HourReq a = list.get(i), b = list.get(j);
            if (a.weekday() == b.weekday() && fe.isEmpty() && LocalTime.parse(a.start()).isBefore(LocalTime.parse(b.end())) && LocalTime.parse(b.start()).isBefore(LocalTime.parse(a.end())))
                fe.add("hours[" + j + "].start", "This time overlaps another time on the same day");
        }
        fe.throwIfAny();
        jdbc.sql("DELETE FROM legal.working_hours WHERE tenant_id = :t").param("t", tenantId).update();
        for (HourReq h : list)
            jdbc.sql("INSERT INTO legal.working_hours (tenant_id, weekday, start_time, end_time) VALUES (:t, :w, CAST(:s AS time), CAST(:e AS time))")
                    .param("t", tenantId).param("w", h.weekday()).param("s", h.start()).param("e", h.end()).update();
        audit.record(actor, tenantId, "LEGAL_HOURS_UPDATED", "tenant", tenantId, null);
        return hours(tenantId);
    }

    public List<Map<String, Object>> timeOff(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, starts_at, ends_at, reason FROM legal.time_off WHERE tenant_id = :t AND ends_at > now() - interval '30 days' ORDER BY starts_at")
                .param("t", tenantId).query().listOfRows());
    }

    public record TimeOffReq(Instant startsAt, Instant endsAt, String reason) {}

    @Transactional
    public Map<String, Object> addTimeOff(UUID tenantId, UUID actor, TimeOffReq r) {
        var fe = new FieldErrors();
        fe.when(r.startsAt() == null, "startsAt", "Choose when it starts");
        fe.when(r.endsAt() == null, "endsAt", "Choose when it ends");
        if (r.startsAt() != null && r.endsAt() != null) fe.when(!r.endsAt().isAfter(r.startsAt()), "endsAt", "The end must be after the start");
        fe.throwIfAny();
        UUID id = jdbc.sql("INSERT INTO legal.time_off (tenant_id, starts_at, ends_at, reason) VALUES (:t, :s, :e, :r) RETURNING id")
                .param("t", tenantId).param("s", java.sql.Timestamp.from(r.startsAt())).param("e", java.sql.Timestamp.from(r.endsAt())).param("r", r.reason() == null ? "" : r.reason().trim()).query(UUID.class).single();
        audit.record(actor, tenantId, "LEGAL_TIME_OFF_ADDED", "tenant", tenantId, null);
        return Map.of("id", id);
    }

    @Transactional
    public void deleteTimeOff(UUID tenantId, UUID id) {
        jdbc.sql("DELETE FROM legal.time_off WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
    }

    /** The sign-up list and the practice areas known to the platform. */
    public static List<Map<String, Object>> areaCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] a : LawyerProvisioner.AREAS) out.add(Map.of("key", a[0], "nameAr", a[1], "nameEn", a[2], "icon", a[3]));
        return out;
    }
}

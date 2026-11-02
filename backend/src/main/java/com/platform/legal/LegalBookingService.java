package com.platform.legal;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.DateRange;
import com.platform.shared.FieldErrors;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Website bookings (guests: name + phone, no account), the lawyer's decisions, and the schedule that merges bookings, case steps and notes. */
@Service
public class LegalBookingService {
    static final Set<String> BUSY = Set.of("PENDING", "APPROVED");
    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final LegalProfileService profiles;
    private final LegalCaseService cases;
    private final ApplicationEventPublisher events;

    public LegalBookingService(JdbcClient jdbc, AuditService audit, LegalProfileService profiles, LegalCaseService cases, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.profiles = profiles;
        this.cases = cases;
        this.events = events;
    }

    // ---- slots -----------------------------------------------------------------------------------------------------

    /** Free start times on one day for a service: inside working hours, outside time off, not overlapping a live booking or a case step. */
    public List<Instant> slots(UUID tenantId, UUID serviceId, LocalDate date, boolean enforceRules) {
        var p = jdbc.sql("SELECT slot_minutes, buffer_minutes, min_notice_hours, max_days_ahead, max_per_day, booking_enabled FROM legal.profiles WHERE tenant_id = :t").param("t", tenantId).query().singleRow();
        if (enforceRules && !Boolean.TRUE.equals(p.get("booking_enabled"))) return List.of();
        int duration = serviceId == null ? ((Number) p.get("slot_minutes")).intValue()
                : jdbc.sql("SELECT duration_minutes FROM legal.services WHERE id = :s AND tenant_id = :t AND is_active").param("s", serviceId).param("t", tenantId).query(Integer.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Service not found"));
        int step = ((Number) p.get("slot_minutes")).intValue();
        int buffer = ((Number) p.get("buffer_minutes")).intValue();
        ZoneId tz = profiles.zone(tenantId);
        Instant now = Instant.now();
        if (enforceRules) {
            LocalDate today = LocalDate.now(tz);
            if (date.isBefore(today) || date.isAfter(today.plusDays(((Number) p.get("max_days_ahead")).intValue()))) return List.of();
        }
        Instant earliest = enforceRules ? now.plus(((Number) p.get("min_notice_hours")).intValue(), ChronoUnit.HOURS) : now.minus(1, ChronoUnit.DAYS);
        var windows = jdbc.sql("SELECT start_time, end_time FROM legal.working_hours WHERE tenant_id = :t AND weekday = :w ORDER BY start_time")
                .param("t", tenantId).param("w", date.getDayOfWeek().getValue()).query((rs, i) -> new LocalTime[]{rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime()}).list();
        if (windows.isEmpty()) return List.of();
        Instant dayStart = date.atStartOfDay(tz).toInstant(), dayEnd = date.plusDays(1).atStartOfDay(tz).toInstant();
        List<Instant[]> busy = new ArrayList<>();
        jdbc.sql("SELECT starts_at, ends_at FROM legal.bookings WHERE tenant_id = :t AND status IN ('PENDING','APPROVED') AND starts_at < :de AND ends_at > :ds")
                .param("t", tenantId).param("de", ts(dayEnd)).param("ds", ts(dayStart)).query((rs, i) -> busy.add(new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()})).list();
        jdbc.sql("SELECT starts_at, starts_at + make_interval(mins => duration_minutes) FROM legal.case_steps WHERE tenant_id = :t AND NOT done AND starts_at < :de AND starts_at + make_interval(mins => duration_minutes) > :ds")
                .param("t", tenantId).param("de", ts(dayEnd)).param("ds", ts(dayStart)).query((rs, i) -> busy.add(new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()})).list();
        jdbc.sql("SELECT starts_at, ends_at FROM legal.time_off WHERE tenant_id = :t AND starts_at < :de AND ends_at > :ds")
                .param("t", tenantId).param("de", ts(dayEnd)).param("ds", ts(dayStart)).query((rs, i) -> busy.add(new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()})).list();
        if (enforceRules && p.get("max_per_day") instanceof Number max) {
            long taken = jdbc.sql("SELECT count(*) FROM legal.bookings WHERE tenant_id = :t AND status IN ('PENDING','APPROVED') AND starts_at >= :ds AND starts_at < :de")
                    .param("t", tenantId).param("ds", ts(dayStart)).param("de", ts(dayEnd)).query(Long.class).single();
            if (taken >= max.longValue()) return List.of();
        }
        List<Instant> out = new ArrayList<>();
        for (LocalTime[] w : windows) {
            int startMin = w[0].toSecondOfDay() / 60, endMin = w[1].toSecondOfDay() / 60;
            for (int m = startMin; m + duration <= endMin; m += step) {
                Instant start = date.atTime(LocalTime.ofSecondOfDay(m * 60L)).atZone(tz).toInstant(), end = start.plus(duration + buffer, ChronoUnit.MINUTES);
                if (start.isBefore(earliest)) continue;
                boolean clash = busy.stream().anyMatch(b -> start.isBefore(b[1].plus(buffer, ChronoUnit.MINUTES)) && b[0].isBefore(end));
                if (!clash) out.add(start);
            }
        }
        return out;
    }

    /** The next days that have at least one free time (for the booking calendar), up to {@code days} ahead. */
    public List<Map<String, Object>> availability(UUID tenantId, UUID serviceId, LocalDate from, int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate start = from == null ? LocalDate.now(profiles.zone(tenantId)) : from;
        for (int i = 0; i < Math.min(Math.max(days, 1), 60); i++) {
            LocalDate d = start.plusDays(i);
            out.add(Map.of("date", d.toString(), "free", slots(tenantId, serviceId, d, true).size()));
        }
        return out;
    }

    // ---- guest booking -------------------------------------------------------------------------------------------------

    public record BookingReq(UUID serviceId, Instant startsAt, String name, String phone, String topic) {}

    @Transactional
    public Map<String, Object> request(UUID tenantId, BookingReq r) {
        var fe = new FieldErrors();
        fe.when(r.name() == null || r.name().trim().length() < 2, "name", "Write your name");
        String phone = PhoneNormalizer.tryNormalize(r.phone());
        fe.when(phone == null, "phone", "Write a phone number we can call");
        fe.when(r.startsAt() == null, "startsAt", "Choose a time");
        fe.when(r.topic() != null && r.topic().length() > 1000, "topic", "Keep it under 1000 letters");
        fe.throwIfAny();
        var p = jdbc.sql("SELECT auto_approve, booking_enabled FROM legal.profiles WHERE tenant_id = :t").param("t", tenantId).query().singleRow();
        if (!Boolean.TRUE.equals(p.get("booking_enabled"))) throw BusinessException.badRequest("BOOKING_CLOSED", "Online booking is closed right now; please call the office");
        // a phone may hold at most 3 upcoming requests (stops spam without OTP)
        long open = jdbc.sql("SELECT count(*) FROM legal.bookings WHERE tenant_id = :t AND guest_phone = :p AND status IN ('PENDING','APPROVED') AND starts_at > now()")
                .param("t", tenantId).param("p", phone).query(Long.class).single();
        if (open >= 3) throw BusinessException.conflict("TOO_MANY_BOOKINGS", "You already have upcoming bookings with this phone; please wait for them or call the office").field("phone");
        ZoneId tz = profiles.zone(tenantId);
        // lock the office's bookings so two visitors can never take the same time
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(CAST(:t AS varchar)))").param("t", tenantId.toString()).query().listOfRows();
        LocalDate day = r.startsAt().atZone(tz).toLocalDate();
        if (!slots(tenantId, r.serviceId(), day, true).contains(r.startsAt()))
            throw BusinessException.conflict("SLOT_UNAVAILABLE", "This time was just taken; please choose another").field("startsAt");
        int duration = r.serviceId() == null ? jdbc.sql("SELECT slot_minutes FROM legal.profiles WHERE tenant_id = :t").param("t", tenantId).query(Integer.class).single()
                : jdbc.sql("SELECT duration_minutes FROM legal.services WHERE id = :s").param("s", r.serviceId()).query(Integer.class).single();
        boolean auto = Boolean.TRUE.equals(p.get("auto_approve"));
        String code = code();
        UUID id = jdbc.sql("""
                INSERT INTO legal.bookings (tenant_id, code, service_id, guest_name, guest_phone, topic, starts_at, ends_at, status)
                VALUES (:t, :c, :s, :n, :p, :tp, :st, :en, :status) RETURNING id
                """).param("t", tenantId).param("c", code).param("s", r.serviceId()).param("n", r.name().trim()).param("p", phone).param("tp", r.topic() == null ? "" : r.topic().trim())
                .param("st", ts(r.startsAt())).param("en", ts(r.startsAt().plus(duration, ChronoUnit.MINUTES))).param("status", auto ? "APPROVED" : "PENDING").query(UUID.class).single();
        if (auto) jdbc.sql("UPDATE legal.bookings SET client_id = :c WHERE id = :i").param("c", cases.clientForPhone(tenantId, r.name(), phone)).param("i", id).update();
        audit.record(null, tenantId, "LEGAL_BOOKING_REQUESTED", "legal_booking", id, null);
        events.publishEvent(new LegalEvents.BookingRequested(tenantId, id, r.name().trim(), r.startsAt(), auto));
        return track(tenantId, code, phone);
    }

    /** A guest checks a request with its code and the phone it was made with. */
    public Map<String, Object> track(UUID tenantId, String code, String rawPhone) {
        String phone = rawPhone == null ? "" : java.util.Objects.requireNonNullElse(PhoneNormalizer.tryNormalize(rawPhone), "");
        var r = jdbc.sql("""
                SELECT b.code, b.guest_name, b.starts_at, b.ends_at, b.status, b.decision_note, b.topic, s.name AS service_name, s.mode, s.price_minor
                FROM legal.bookings b LEFT JOIN legal.services s ON s.id = b.service_id WHERE b.tenant_id = :t AND upper(b.code) = upper(:c) AND b.guest_phone = :p
                """).param("t", tenantId).param("c", code == null ? "" : code.trim()).param("p", phone).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("BOOKING_NOT_FOUND", "No booking with this code and phone"));
        return Rows.camel(r);
    }

    @Transactional
    public Map<String, Object> cancelByGuest(UUID tenantId, String code, String rawPhone) {
        Map<String, Object> b = track(tenantId, code, rawPhone);
        if (!BUSY.contains((String) b.get("status"))) throw BusinessException.conflict("BOOKING_CLOSED", "This booking can no longer be cancelled");
        UUID id = jdbc.sql("UPDATE legal.bookings SET status = 'CANCELLED', decision_note = 'Cancelled by the client', updated_at = now() WHERE tenant_id = :t AND upper(code) = upper(:c) RETURNING id")
                .param("t", tenantId).param("c", code.trim()).query(UUID.class).single();
        events.publishEvent(new LegalEvents.BookingCancelled(tenantId, id, (String) b.get("guestName"), ((java.time.Instant) b.get("startsAt"))));
        return track(tenantId, code, rawPhone);
    }

    // ---- staff ---------------------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, Page page, String status, String q, LocalDate from, LocalDate to, boolean upcoming) {
        DateRange.check(from, to);
        List<String> st = status == null || status.isBlank() ? List.of() : java.util.Arrays.stream(status.split(",")).map(String::trim).toList();
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = """
                FROM legal.bookings b LEFT JOIN legal.services s ON s.id = b.service_id WHERE b.tenant_id = :t AND (:sn = 0 OR b.status = ANY(string_to_array(:ss, ',')))
                AND (CAST(:q AS varchar) IS NULL OR b.guest_name ILIKE CAST(:q AS varchar) OR b.guest_phone LIKE CAST(:q AS varchar) OR b.code ILIKE CAST(:q AS varchar) OR b.topic ILIKE CAST(:q AS varchar))
                AND (NOT :up OR b.starts_at > now() - interval '1 hour') AND """ + DateRange.sql("b.starts_at", "t");
        java.util.function.UnaryOperator<JdbcClient.StatementSpec> bind = x -> x.param("t", tenantId).param("sn", st.size()).param("ss", String.join(",", st)).param("q", like).param("up", upcoming).param("from", from).param("to", to);
        long total = bind.apply(jdbc.sql("SELECT count(*) " + where)).query(Long.class).single();
        var rows = bind.apply(jdbc.sql("SELECT b.id, b.code, b.guest_name, b.guest_phone, b.topic, b.starts_at, b.ends_at, b.status, b.decision_note, b.client_id, b.case_id, b.created_at, s.name AS service_name, s.mode, s.price_minor "
                + where + " ORDER BY CASE WHEN b.status = 'PENDING' THEN 0 ELSE 1 END, b.starts_at " + (upcoming ? "ASC" : "DESC") + " LIMIT :lim OFFSET :off"))
                .param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>(page.wrap(Rows.camel(rows), total));
        out.put("counts", Rows.camel(jdbc.sql("SELECT count(*) FILTER (WHERE status = 'PENDING' AND starts_at > now()) AS pending, count(*) FILTER (WHERE status = 'APPROVED' AND starts_at > now()) AS approved FROM legal.bookings WHERE tenant_id = :t")
                .param("t", tenantId).query().singleRow()));
        return out;
    }

    public record DecisionReq(String status, String note, Instant startsAt, UUID caseId) {}

    /** APPROVED / DENIED / CANCELLED / DONE / NO_SHOW, optionally moving the time (re-checked against the schedule) or tying it to a case. */
    @Transactional
    public Map<String, Object> decide(UUID tenantId, UUID actor, UUID id, DecisionReq r) {
        var b = jdbc.sql("SELECT * FROM legal.bookings WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Booking not found"));
        String to = r.status() == null ? (String) b.get("status") : r.status();
        if (!Set.of("PENDING", "APPROVED", "DENIED", "CANCELLED", "DONE", "NO_SHOW").contains(to)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status").field("status");
        if ("DENIED".equals(to) && (r.note() == null || r.note().isBlank()) && !"DENIED".equals(b.get("status")))
            throw BusinessException.badRequest("VALIDATION_ERROR", "Tell the client why (it is shown on their booking page)").field("note");
        Instant start = ((java.sql.Timestamp) b.get("starts_at")).toInstant(), end = ((java.sql.Timestamp) b.get("ends_at")).toInstant();
        if (r.startsAt() != null && !r.startsAt().equals(start)) {
            long minutes = ChronoUnit.MINUTES.between(start, end);
            jdbc.sql("UPDATE legal.bookings SET status = 'CANCELLED' WHERE id = :i").param("i", id).update();   // free its own time for the check
            if (!slots(tenantId, (UUID) b.get("service_id"), r.startsAt().atZone(profiles.zone(tenantId)).toLocalDate(), false).contains(r.startsAt()))
                throw BusinessException.conflict("SLOT_UNAVAILABLE", "That time is not free").field("startsAt");
            start = r.startsAt();
            end = start.plus(minutes, ChronoUnit.MINUTES);
        }
        if (r.caseId() != null) cases.requireCase(tenantId, r.caseId());
        UUID client = (UUID) b.get("client_id");
        if ("APPROVED".equals(to) && client == null) client = cases.clientForPhone(tenantId, (String) b.get("guest_name"), (String) b.get("guest_phone"));
        jdbc.sql("""
                UPDATE legal.bookings SET status = :s, decision_note = coalesce(:n, decision_note), starts_at = :st, ends_at = :en, client_id = :c, case_id = coalesce(:k, case_id),
                  reminder_sent_at = CASE WHEN starts_at <> :st THEN NULL ELSE reminder_sent_at END, updated_at = now() WHERE id = :i
                """).param("s", to).param("n", r.note() == null ? null : r.note().trim()).param("st", ts(start)).param("en", ts(end)).param("c", client).param("k", r.caseId()).param("i", id).update();
        audit.record(actor, tenantId, "LEGAL_BOOKING_" + to, "legal_booking", id, null);
        boolean moved = !start.equals(((java.sql.Timestamp) b.get("starts_at")).toInstant());
        if (client != null && (moved || !to.equals(b.get("status"))) && Set.of("APPROVED", "DENIED", "CANCELLED").contains(to))
            events.publishEvent(new LegalEvents.BookingDecided(tenantId, id, client, to, start, moved, r.note()));
        return Rows.camel(jdbc.sql("SELECT b.*, s.name AS service_name FROM legal.bookings b LEFT JOIN legal.services s ON s.id = b.service_id WHERE b.id = :i").param("i", id).query().singleRow());
    }

    /** The lawyer books someone in by phone/at the desk (no rules on notice, still no double booking). */
    @Transactional
    public Map<String, Object> createByStaff(UUID tenantId, UUID actor, BookingReq r, UUID caseId) {
        var fe = new FieldErrors();
        fe.when(r.name() == null || r.name().isBlank(), "name", "Write the client's name");
        fe.when(r.startsAt() == null, "startsAt", "Choose a time");
        fe.throwIfAny();
        String phone = r.phone() == null || r.phone().isBlank() ? "" : PhoneNormalizer.tryNormalize(r.phone());
        if (phone == null) throw BusinessException.badRequest("INVALID_PHONE", "Check the phone number").field("phone");
        int duration = r.serviceId() == null ? jdbc.sql("SELECT slot_minutes FROM legal.profiles WHERE tenant_id = :t").param("t", tenantId).query(Integer.class).single()
                : jdbc.sql("SELECT duration_minutes FROM legal.services WHERE id = :s AND tenant_id = :t").param("s", r.serviceId()).param("t", tenantId).query(Integer.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("VALIDATION_ERROR", "Service not found").field("serviceId"));
        if (caseId != null) cases.requireCase(tenantId, caseId);
        UUID client = cases.clientForPhone(tenantId, r.name(), phone);
        UUID id = jdbc.sql("""
                INSERT INTO legal.bookings (tenant_id, code, service_id, client_id, case_id, guest_name, guest_phone, topic, starts_at, ends_at, status, source)
                VALUES (:t, :c, :s, :cl, :k, :n, :p, :tp, :st, :en, 'APPROVED', 'OFFICE') RETURNING id
                """).param("t", tenantId).param("c", code()).param("s", r.serviceId()).param("cl", client).param("k", caseId).param("n", r.name().trim()).param("p", phone)
                .param("tp", r.topic() == null ? "" : r.topic().trim()).param("st", ts(r.startsAt())).param("en", ts(r.startsAt().plus(duration, ChronoUnit.MINUTES))).query(UUID.class).single();
        audit.record(actor, tenantId, "LEGAL_BOOKING_APPROVED", "legal_booking", id, "{\"by\":\"staff\"}");
        return Rows.camel(jdbc.sql("SELECT * FROM legal.bookings WHERE id = :i").param("i", id).query().singleRow());
    }

    // ---- schedule ------------------------------------------------------------------------------------------------------

    /** Everything on the calendar between two days: approved/pending bookings, case steps and notes, each with a kind for its colour. */
    public List<Map<String, Object>> schedule(UUID tenantId, LocalDate from, LocalDate to) {
        DateRange.check(from, to);
        ZoneId tz = profiles.zone(tenantId);
        LocalDate f = from == null ? LocalDate.now(tz).minusDays(7) : from, t = to == null ? f.plusDays(42) : to;
        if (ChronoUnit.DAYS.between(f, t) > 400) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose up to a year").field("to");
        Instant s = f.atStartOfDay(tz).toInstant(), e = t.plusDays(1).atStartOfDay(tz).toInstant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT b.id, b.starts_at, b.ends_at, b.status, b.guest_name, b.guest_phone, b.topic, b.code, b.case_id, k.number AS case_number, sv.name AS service_name
                FROM legal.bookings b LEFT JOIN legal.cases k ON k.id = b.case_id LEFT JOIN legal.services sv ON sv.id = b.service_id
                WHERE b.tenant_id = :t AND b.status IN ('PENDING','APPROVED','DONE') AND b.starts_at < :e AND b.ends_at > :s
                """).param("t", tenantId).param("s", ts(s)).param("e", ts(e)).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "BOOKING"); m.put("id", r.get("id")); m.put("startsAt", inst(r.get("starts_at"))); m.put("endsAt", inst(r.get("ends_at")));
            m.put("title", r.get("guest_name") + (r.get("service_name") == null ? "" : " — " + r.get("service_name"))); m.put("status", r.get("status"));
            m.put("phone", r.get("guest_phone")); m.put("body", r.get("topic")); m.put("caseId", r.get("case_id")); m.put("caseNumber", r.get("case_number")); m.put("code", r.get("code"));
            out.add(m);
        }
        for (var r : jdbc.sql("""
                SELECT st.id, st.kind AS step_kind, st.starts_at, st.duration_minutes, st.description, st.location, st.done, k.id AS case_id, k.number, k.title
                FROM legal.case_steps st JOIN legal.cases k ON k.id = st.case_id WHERE st.tenant_id = :t AND st.starts_at >= :s AND st.starts_at < :e
                """).param("t", tenantId).param("s", ts(s)).param("e", ts(e)).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Instant st = inst(r.get("starts_at"));
            m.put("kind", "STEP"); m.put("id", r.get("id")); m.put("stepKind", r.get("step_kind")); m.put("startsAt", st); m.put("endsAt", st.plus(((Number) r.get("duration_minutes")).longValue(), ChronoUnit.MINUTES));
            m.put("title", r.get("description")); m.put("location", r.get("location")); m.put("done", r.get("done"));
            m.put("caseId", r.get("case_id")); m.put("caseNumber", r.get("number")); m.put("caseTitle", r.get("title"));
            out.add(m);
        }
        for (var r : jdbc.sql("""
                SELECT n.id, n.starts_at, n.all_day, n.title, n.body, n.color, n.case_id, k.number, k.title AS case_title FROM legal.calendar_notes n LEFT JOIN legal.cases k ON k.id = n.case_id
                WHERE n.tenant_id = :t AND n.starts_at >= :s AND n.starts_at < :e
                """).param("t", tenantId).param("s", ts(s)).param("e", ts(e)).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Instant st = inst(r.get("starts_at"));
            m.put("kind", "NOTE"); m.put("id", r.get("id")); m.put("startsAt", st); m.put("endsAt", st.plus(30, ChronoUnit.MINUTES)); m.put("allDay", r.get("all_day"));
            m.put("title", r.get("title")); m.put("body", r.get("body")); m.put("color", r.get("color"));
            m.put("caseId", r.get("case_id")); m.put("caseNumber", r.get("number")); m.put("caseTitle", r.get("case_title"));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (Instant) m.get("startsAt")));
        return out;
    }

    public record NoteReq(Instant startsAt, Boolean allDay, String title, String body, String color, UUID caseId,
                          /* create a case on the fly from the schedule */ LegalCaseService.CaseReq newCase) {}

    /** A note (optionally tied to an existing case, or to a new one created right here). */
    @Transactional
    public Map<String, Object> addNote(UUID tenantId, UUID actor, NoteReq r) {
        var fe = new FieldErrors();
        fe.when(r.startsAt() == null, "startsAt", "Choose the day and time");
        fe.when(r.title() == null || r.title().isBlank(), "title", "Write the note");
        fe.throwIfAny();
        UUID caseId = r.caseId();
        if (caseId == null && r.newCase() != null) {
            try { caseId = (UUID) cases.createCase(tenantId, actor, r.newCase()).get("id"); }
            catch (BusinessException e) { throw prefix(e, "newCase."); }
        }
        if (caseId != null) cases.requireCase(tenantId, caseId);
        UUID id = jdbc.sql("INSERT INTO legal.calendar_notes (tenant_id, case_id, starts_at, all_day, title, body, color, created_by) VALUES (:t, :k, :s, :a, :ti, :b, :c, :u) RETURNING id")
                .param("t", tenantId).param("k", caseId).param("s", ts(r.startsAt())).param("a", Boolean.TRUE.equals(r.allDay())).param("ti", r.title().trim()).param("b", r.body() == null ? "" : r.body().trim())
                .param("c", r.color() == null ? "" : r.color()).param("u", actor).query(UUID.class).single();
        audit.record(actor, tenantId, "LEGAL_NOTE_ADDED", "legal_note", id, null);
        return Map.of("id", id, "caseId", caseId == null ? "" : caseId);
    }

    public record StepFromScheduleReq(UUID caseId, LegalCaseService.CaseReq newCase, LegalCaseService.StepReq step) {}

    /** A case step added from the schedule, on an existing case or a new one made right here. */
    @Transactional
    public Map<String, Object> addStepFromSchedule(UUID tenantId, UUID actor, StepFromScheduleReq r) {
        UUID caseId = r.caseId();
        if (caseId == null && r.newCase() != null) {
            try { caseId = (UUID) cases.createCase(tenantId, actor, r.newCase()).get("id"); }
            catch (BusinessException e) { throw prefix(e, "newCase."); }
        }
        if (caseId == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Pick a case or create a new one").field("caseId");
        if (r.step() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Describe this step").field("step.description");
        try { return cases.addStep(tenantId, actor, caseId, r.step()); }
        catch (BusinessException e) { throw prefix(e, "step."); }
    }

    @Transactional
    public void updateNote(UUID tenantId, UUID actor, UUID id, NoteReq r) {
        var fe = new FieldErrors();
        fe.when(r.startsAt() == null, "startsAt", "Choose the day and time");
        fe.when(r.title() == null || r.title().isBlank(), "title", "Write the note");
        fe.throwIfAny();
        if (r.caseId() != null) cases.requireCase(tenantId, r.caseId());
        if (jdbc.sql("""
                UPDATE legal.calendar_notes SET starts_at = :s, all_day = :a, title = :ti, body = :b, color = :c, case_id = :k,
                  reminder_sent_at = CASE WHEN starts_at <> :s THEN NULL ELSE reminder_sent_at END WHERE id = :i AND tenant_id = :t
                """).param("s", ts(r.startsAt())).param("a", Boolean.TRUE.equals(r.allDay())).param("ti", r.title().trim()).param("b", r.body() == null ? "" : r.body().trim())
                .param("c", r.color() == null ? "" : r.color()).param("k", r.caseId()).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Note not found");
    }

    @Transactional
    public void deleteNote(UUID tenantId, UUID id) {
        jdbc.sql("DELETE FROM legal.calendar_notes WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
    }

    public record MoveReq(String kind, UUID id, Instant startsAt) {}

    /** Drag on the calendar: moves a booking, step or note. */
    @Transactional
    public void move(UUID tenantId, UUID actor, MoveReq r) {
        if (r.startsAt() == null || r.id() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose the new time").field("startsAt");
        switch (r.kind() == null ? "" : r.kind()) {
            case "BOOKING" -> decide(tenantId, actor, r.id(), new DecisionReq(null, null, r.startsAt(), null));
            case "STEP" -> {
                if (jdbc.sql("UPDATE legal.case_steps SET starts_at = :s, reminder_sent_at = NULL, early_reminder_sent_at = NULL WHERE id = :i AND tenant_id = :t")
                        .param("s", ts(r.startsAt())).param("i", r.id()).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Step not found");
            }
            case "NOTE" -> {
                if (jdbc.sql("UPDATE legal.calendar_notes SET starts_at = :s, reminder_sent_at = NULL WHERE id = :i AND tenant_id = :t")
                        .param("s", ts(r.startsAt())).param("i", r.id()).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Note not found");
            }
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown item").field("kind");
        }
        audit.record(actor, tenantId, "LEGAL_SCHEDULE_MOVED", "legal_" + r.kind().toLowerCase(), r.id(), null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static BusinessException prefix(BusinessException e, String p) {
        if (e.getFields().isEmpty()) return e;
        Map<String, String> f = new LinkedHashMap<>();
        e.getFields().forEach((k, v) -> f.put(p + k, v));
        return new BusinessException(e.getStatus(), e.getCode(), e.getMessage()).fields(f);
    }

    private static String code() {
        StringBuilder sb = new StringBuilder("B");
        for (int i = 0; i < 6; i++) sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    static java.sql.Timestamp ts(Instant i) { return java.sql.Timestamp.from(i); }

    static Instant inst(Object o) { return o instanceof java.sql.Timestamp t ? t.toInstant() : (Instant) o; }
}

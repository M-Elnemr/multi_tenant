package com.platform.legal;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.DateRange;
import com.platform.shared.FieldErrors;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Clients, cases and everything on a case: actions with fees, dated steps, payments and documents; plus finance and the deep search. */
@Service
public class LegalCaseService {
    public static final Set<String> CASE_TYPES = Set.of("CIVIL", "CRIMINAL", "FAMILY", "COMMERCIAL", "LABOUR", "REAL_ESTATE", "ADMINISTRATIVE", "ECONOMIC", "INHERITANCE",
            "CONTRACTS", "IP", "TAX", "ARBITRATION", "CASSATION", "CONSULTATION", "OTHER");
    static final Set<String> STATUSES = Set.of("UNDER_STUDY", "OPEN", "CLOSED");
    static final Set<String> OUTCOMES = Set.of("NONE", "WON", "LOST", "SETTLED");
    static final Set<String> STEP_KINDS = Set.of("HEARING", "DEADLINE", "FILING", "MEETING", "OTHER");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final com.platform.files.FileService files;

    public LegalCaseService(JdbcClient jdbc, AuditService audit, com.platform.files.FileService files) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.files = files;
    }

    // ---- clients -----------------------------------------------------------------------------------------------

    public record ClientReq(String name, String phone, String phone2, String nationalId, String email, String address, String kind, String notes, List<String> tags) {}

    public Map<String, Object> clients(UUID tenantId, Page page, String q, LocalDate from, LocalDate to, String tag) {
        DateRange.check(from, to);
        String like = like(q);
        String where = """
                FROM legal.clients c WHERE c.tenant_id = :t AND (CAST(:q AS varchar) IS NULL OR c.name ILIKE CAST(:q AS varchar) OR c.phone LIKE CAST(:q AS varchar) OR c.national_id LIKE CAST(:q AS varchar) OR c.email ILIKE CAST(:q AS varchar))
                AND (CAST(:tag AS varchar) IS NULL OR CAST(:tag AS varchar) = ANY(c.tags)) AND """ + DateRange.sql("c.created_at", "t");
        long total = jdbc.sql("SELECT count(*) " + where).param("t", tenantId).param("q", like).param("tag", blank(tag)).param("from", from).param("to", to).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT c.id, c.name, c.phone, c.email, c.kind, c.tags, c.created_at,
                  (SELECT count(*) FROM legal.case_clients cc JOIN legal.cases k ON k.id = cc.case_id WHERE cc.client_id = c.id) AS case_count,
                  (SELECT count(*) FROM legal.case_clients cc JOIN legal.cases k ON k.id = cc.case_id WHERE cc.client_id = c.id AND k.status <> 'CLOSED') AS open_cases,
                  coalesce((SELECT sum(k.agreed_fee_minor + (SELECT coalesce(sum(a.fee_minor), 0) FROM legal.case_actions a WHERE a.case_id = k.id)) FROM legal.case_clients cc JOIN legal.cases k ON k.id = cc.case_id WHERE cc.client_id = c.id), 0)
                   - coalesce((SELECT sum(p.amount_minor) FROM legal.payments p JOIN legal.case_clients cc ON cc.case_id = p.case_id WHERE cc.client_id = c.id), 0) AS balance_minor
                """ + where + " ORDER BY c.created_at DESC LIMIT :lim OFFSET :off")
                .param("t", tenantId).param("q", like).param("tag", blank(tag)).param("from", from).param("to", to).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) { var m = Rows.camel(r); m.put("tags", arr(r.get("tags"))); out.add(m); }
        return page.wrap(out, total);
    }

    public Map<String, Object> client(UUID tenantId, UUID id) {
        var r = jdbc.sql("SELECT * FROM legal.clients WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Client not found"));
        Map<String, Object> m = Rows.camel(r);
        m.put("tags", arr(r.get("tags")));
        m.put("cases", Rows.camel(jdbc.sql("""
                SELECT k.id, k.number, k.title, k.case_type, k.status, k.outcome, k.opened_on, k.court,
                  k.agreed_fee_minor + (SELECT coalesce(sum(a.fee_minor), 0) FROM legal.case_actions a WHERE a.case_id = k.id) AS billed_minor,
                  (SELECT coalesce(sum(p.amount_minor), 0) FROM legal.payments p WHERE p.case_id = k.id) AS paid_minor,
                  (SELECT min(s.starts_at) FROM legal.case_steps s WHERE s.case_id = k.id AND NOT s.done AND s.starts_at > now()) AS next_step_at
                FROM legal.cases k JOIN legal.case_clients cc ON cc.case_id = k.id WHERE cc.client_id = :c ORDER BY k.opened_on DESC
                """).param("c", id).query().listOfRows()));
        m.put("bookings", Rows.camel(jdbc.sql("SELECT id, code, starts_at, status, topic FROM legal.bookings WHERE tenant_id = :t AND (client_id = :c OR (guest_phone = :p AND :p <> '')) ORDER BY starts_at DESC LIMIT 20")
                .param("t", tenantId).param("c", id).param("p", r.get("phone")).query().listOfRows()));
        long billed = ((List<?>) m.get("cases")).stream().mapToLong(x -> ((Number) ((Map<?, ?>) x).get("billedMinor")).longValue()).sum();
        long paid = ((List<?>) m.get("cases")).stream().mapToLong(x -> ((Number) ((Map<?, ?>) x).get("paidMinor")).longValue()).sum();
        m.put("billedMinor", billed);
        m.put("paidMinor", paid);
        m.put("balanceMinor", billed - paid);
        return m;
    }

    private void checkClient(ClientReq r) {
        var fe = new FieldErrors();
        fe.when(r.name() == null || r.name().trim().length() < 2, "name", "Write the client's name");
        if (r.phone() != null && !r.phone().isBlank()) fe.when(PhoneNormalizer.tryNormalize(r.phone()) == null, "phone", "Check the phone number");
        if (r.phone2() != null && !r.phone2().isBlank()) fe.when(PhoneNormalizer.tryNormalize(r.phone2()) == null, "phone2", "Check the phone number");
        if (r.email() != null && !r.email().isBlank()) fe.when(!r.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"), "email", "This email does not look right");
        if (r.nationalId() != null && !r.nationalId().isBlank()) fe.when(!PhoneNormalizer.latinDigits(r.nationalId()).matches("[0-9A-Za-z-]{5,30}"), "nationalId", "Check the ID number");
        fe.when(r.kind() != null && !Set.of("PERSON", "COMPANY").contains(r.kind()), "kind", "Choose person or company");
        fe.throwIfAny();
    }

    private static String phone(String raw) { return raw == null || raw.isBlank() ? "" : java.util.Objects.requireNonNullElse(PhoneNormalizer.tryNormalize(raw), ""); }

    @Transactional
    public Map<String, Object> createClient(UUID tenantId, UUID actor, ClientReq r) {
        checkClient(r);
        try {
            UUID id = jdbc.sql("""
                    INSERT INTO legal.clients (tenant_id, name, phone, phone2, national_id, email, address, kind, notes, tags)
                    VALUES (:t, :n, :p, :p2, :ni, :e, :a, :k, :no, string_to_array(:tg, ',')) RETURNING id
                    """).param("t", tenantId).param("n", r.name().trim()).param("p", phone(r.phone())).param("p2", phone(r.phone2())).param("ni", nz(PhoneNormalizer.latinDigits(nz(r.nationalId()))))
                    .param("e", nz(r.email())).param("a", nz(r.address())).param("k", r.kind() == null ? "PERSON" : r.kind()).param("no", nz(r.notes())).param("tg", tags(r.tags())).query(UUID.class).single();
            audit.record(actor, tenantId, "LEGAL_CLIENT_CREATED", "legal_client", id, null);
            return client(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("CLIENT_PHONE_TAKEN", "A client with this phone already exists").field("phone");
        }
    }

    @Transactional
    public Map<String, Object> updateClient(UUID tenantId, UUID actor, UUID id, ClientReq r) {
        checkClient(r);
        try {
            int n = jdbc.sql("""
                    UPDATE legal.clients SET name = :n, phone = :p, phone2 = :p2, national_id = :ni, email = :e, address = :a, kind = :k, notes = :no, tags = string_to_array(:tg, ','), updated_at = now()
                    WHERE id = :i AND tenant_id = :t
                    """).param("n", r.name().trim()).param("p", phone(r.phone())).param("p2", phone(r.phone2())).param("ni", nz(PhoneNormalizer.latinDigits(nz(r.nationalId()))))
                    .param("e", nz(r.email())).param("a", nz(r.address())).param("k", r.kind() == null ? "PERSON" : r.kind()).param("no", nz(r.notes())).param("tg", tags(r.tags()))
                    .param("i", id).param("t", tenantId).update();
            if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Client not found");
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("CLIENT_PHONE_TAKEN", "A client with this phone already exists").field("phone");
        }
        audit.record(actor, tenantId, "LEGAL_CLIENT_UPDATED", "legal_client", id, null);
        return client(tenantId, id);
    }

    @Transactional
    public void deleteClient(UUID tenantId, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM legal.clients WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Client not found");
        audit.record(actor, tenantId, "LEGAL_CLIENT_DELETED", "legal_client", id, null);
    }

    /** The booking's guest becomes a client (or is matched to an existing one by phone). */
    @Transactional
    public UUID clientForPhone(UUID tenantId, String name, String rawPhone) {
        String p = phone(rawPhone);
        if (!p.isEmpty()) {
            var existing = jdbc.sql("SELECT id FROM legal.clients WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", p).query(UUID.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        return jdbc.sql("INSERT INTO legal.clients (tenant_id, name, phone) VALUES (:t, :n, :p) RETURNING id").param("t", tenantId).param("n", name.trim()).param("p", p).query(UUID.class).single();
    }

    // ---- cases ---------------------------------------------------------------------------------------------------

    public record CaseReq(String title, String caseType, String court, String circuit, String courtCaseNo, String opponent, String opponentLawyer, String clientRole,
                          String status, String outcome, LocalDate openedOn, LocalDate closedOn, String description, Long agreedFeeMinor, List<UUID> clientIds, UUID assignedTo) {}

    /** Every filter on the cases screen. statuses/outcomes/types may hold several values (comma separated). */
    public record CaseFilter(String q, String status, String outcome, String type, UUID clientId, String court, LocalDate from, LocalDate to, String sort) {}

    public Map<String, Object> cases(UUID tenantId, Page page, CaseFilter f) {
        DateRange.check(f.from(), f.to());
        String like = like(f.q());
        List<String> st = csv(f.status()), oc = csv(f.outcome()), ty = csv(f.type());
        String where = """
                FROM legal.cases k WHERE k.tenant_id = :t
                AND (:sn = 0 OR k.status = ANY(string_to_array(:ss, ','))) AND (:on = 0 OR k.outcome = ANY(string_to_array(:os, ','))) AND (:tn = 0 OR k.case_type = ANY(string_to_array(:ts, ',')))
                AND (CAST(:cl AS uuid) IS NULL OR EXISTS (SELECT 1 FROM legal.case_clients cc WHERE cc.case_id = k.id AND cc.client_id = CAST(:cl AS uuid)))
                AND (CAST(:co AS varchar) IS NULL OR k.court ILIKE CAST(:co AS varchar))
                AND (CAST(:q AS varchar) IS NULL OR k.title ILIKE CAST(:q AS varchar) OR k.number ILIKE CAST(:q AS varchar) OR k.court_case_no ILIKE CAST(:q AS varchar) OR k.opponent ILIKE CAST(:q AS varchar)
                     OR k.court ILIKE CAST(:q AS varchar) OR EXISTS (SELECT 1 FROM legal.case_clients cc JOIN legal.clients c ON c.id = cc.client_id WHERE cc.case_id = k.id AND (c.name ILIKE CAST(:q AS varchar) OR c.phone LIKE CAST(:q AS varchar))))
                AND """ + DateRange.sqlDate("k.opened_on");
        String order = switch (f.sort() == null ? "" : f.sort().toUpperCase()) {
            case "OLDEST" -> "k.opened_on ASC, k.created_at ASC";
            case "NEXT_STEP" -> "next_step_at ASC NULLS LAST, k.opened_on DESC";
            case "UPDATED" -> "k.updated_at DESC";
            default -> "k.opened_on DESC, k.created_at DESC";
        };
        java.util.function.UnaryOperator<JdbcClient.StatementSpec> bind = s -> s.param("t", tenantId).param("sn", st.size()).param("ss", String.join(",", st))
                .param("on", oc.size()).param("os", String.join(",", oc)).param("tn", ty.size()).param("ts", String.join(",", ty)).param("cl", f.clientId())
                .param("co", like(f.court())).param("q", like).param("from", f.from()).param("to", f.to());
        long total = bind.apply(jdbc.sql("SELECT count(*) " + where)).query(Long.class).single();
        var rows = bind.apply(jdbc.sql("""
                SELECT k.id, k.number, k.title, k.case_type, k.court, k.court_case_no, k.opponent, k.status, k.outcome, k.opened_on, k.closed_on, k.agreed_fee_minor,
                  (SELECT string_agg(c.name, '، ' ORDER BY c.name) FROM legal.case_clients cc JOIN legal.clients c ON c.id = cc.client_id WHERE cc.case_id = k.id) AS client_names,
                  (SELECT min(s.starts_at) FROM legal.case_steps s WHERE s.case_id = k.id AND NOT s.done AND s.starts_at > now()) AS next_step_at,
                  k.agreed_fee_minor + (SELECT coalesce(sum(a.fee_minor), 0) FROM legal.case_actions a WHERE a.case_id = k.id) AS billed_minor,
                  (SELECT coalesce(sum(p.amount_minor), 0) FROM legal.payments p WHERE p.case_id = k.id) AS paid_minor
                """ + where + " ORDER BY " + order + " LIMIT :lim OFFSET :off")).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>(page.wrap(Rows.camel(rows), total));
        // counts for the one-tap chips, under the same search/date/client filters
        var counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPEN') AS open, count(*) FILTER (WHERE status = 'UNDER_STUDY') AS under_study, count(*) FILTER (WHERE status = 'CLOSED') AS closed,
                       count(*) FILTER (WHERE outcome = 'WON') AS won, count(*) FILTER (WHERE outcome = 'LOST') AS lost, count(*) FILTER (WHERE outcome = 'SETTLED') AS settled, count(*) AS total
                FROM legal.cases WHERE tenant_id = :t
                """).param("t", tenantId).query().singleRow();
        out.put("counts", Rows.camel(counts));
        return out;
    }

    public Map<String, Object> caseDetail(UUID tenantId, UUID id) {
        var r = jdbc.sql("SELECT k.*, u.first_name || ' ' || u.last_name AS assigned_name FROM legal.cases k LEFT JOIN core.users u ON u.id = k.assigned_to WHERE k.id = :i AND k.tenant_id = :t")
                .param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Case not found"));
        Map<String, Object> m = Rows.camel(r);
        m.put("clients", Rows.camel(jdbc.sql("SELECT c.id, c.name, c.phone, c.email, c.kind FROM legal.case_clients cc JOIN legal.clients c ON c.id = cc.client_id WHERE cc.case_id = :k ORDER BY c.name")
                .param("k", id).query().listOfRows()));
        m.put("actions", Rows.camel(jdbc.sql("SELECT id, action_date, title, notes, fee_minor, created_at FROM legal.case_actions WHERE case_id = :k ORDER BY action_date DESC, created_at DESC").param("k", id).query().listOfRows()));
        m.put("steps", Rows.camel(jdbc.sql("SELECT id, kind, starts_at, duration_minutes, description, location, done, result FROM legal.case_steps WHERE case_id = :k ORDER BY starts_at").param("k", id).query().listOfRows()));
        m.put("payments", Rows.camel(jdbc.sql("SELECT p.id, p.amount_minor, p.paid_on, p.method, p.note, p.client_id, c.name AS client_name FROM legal.payments p LEFT JOIN legal.clients c ON c.id = p.client_id WHERE p.case_id = :k ORDER BY p.paid_on DESC, p.created_at DESC")
                .param("k", id).query().listOfRows()));
        m.put("documents", Rows.camel(jdbc.sql("SELECT id, name, url, content_type, created_at FROM legal.case_documents WHERE case_id = :k ORDER BY created_at DESC").param("k", id).query().listOfRows()));
        m.put("notes", Rows.camel(jdbc.sql("SELECT id, starts_at, title, body FROM legal.calendar_notes WHERE case_id = :k ORDER BY starts_at DESC LIMIT 50").param("k", id).query().listOfRows()));
        long fees = ((List<?>) m.get("actions")).stream().mapToLong(x -> ((Number) ((Map<?, ?>) x).get("feeMinor")).longValue()).sum();
        long paid = ((List<?>) m.get("payments")).stream().mapToLong(x -> ((Number) ((Map<?, ?>) x).get("amountMinor")).longValue()).sum();
        long agreed = ((Number) r.get("agreed_fee_minor")).longValue();
        m.put("actionFeesMinor", fees);
        m.put("billedMinor", agreed + fees);
        m.put("paidMinor", paid);
        m.put("balanceMinor", agreed + fees - paid);
        return m;
    }

    private void checkCase(CaseReq r) {
        var fe = new FieldErrors();
        fe.when(r.title() == null || r.title().trim().length() < 3, "title", "Give the case a clear title");
        fe.when(r.caseType() != null && !CASE_TYPES.contains(r.caseType()), "caseType", "Choose the case type");
        fe.when(r.status() != null && !STATUSES.contains(r.status()), "status", "Choose the status");
        fe.when(r.outcome() != null && !OUTCOMES.contains(r.outcome()), "outcome", "Choose the result");
        fe.when(r.agreedFeeMinor() != null && r.agreedFeeMinor() < 0, "agreedFeeMinor", "The fee cannot be negative");
        if (r.openedOn() != null && r.closedOn() != null) fe.when(r.closedOn().isBefore(r.openedOn()), "closedOn", "The closing date is before the opening date");
        if (r.outcome() != null && !"NONE".equals(r.outcome()) && "UNDER_STUDY".equals(r.status())) fe.add("outcome", "A case under study has no result yet");
        fe.throwIfAny();
    }

    private String nextNumber(UUID tenantId) {
        int year = LocalDate.now().getYear();
        int seq = jdbc.sql("""
                INSERT INTO legal.counters (tenant_id, year, seq) VALUES (:t, :y, 1) ON CONFLICT (tenant_id, year) DO UPDATE SET seq = legal.counters.seq + 1 RETURNING seq
                """).param("t", tenantId).param("y", year).query(Integer.class).single();
        return year + "-" + String.format("%04d", seq);
    }

    @Transactional
    public Map<String, Object> createCase(UUID tenantId, UUID actor, CaseReq r) {
        checkCase(r);
        String status = r.status() == null ? "OPEN" : r.status();
        String outcome = r.outcome() == null ? "NONE" : r.outcome();
        UUID id = jdbc.sql("""
                INSERT INTO legal.cases (tenant_id, number, title, case_type, court, circuit, court_case_no, opponent, opponent_lawyer, client_role, status, outcome, opened_on, closed_on,
                  description, agreed_fee_minor, assigned_to)
                VALUES (:t, :n, :ti, :ty, :co, :ci, :cn, :op, :ol, :cr, :st, :oc, coalesce(:od, CURRENT_DATE), :cd, :de, :fee, :as) RETURNING id
                """).param("t", tenantId).param("n", nextNumber(tenantId)).param("ti", r.title().trim()).param("ty", r.caseType() == null ? "CIVIL" : r.caseType())
                .param("co", nz(r.court())).param("ci", nz(r.circuit())).param("cn", nz(r.courtCaseNo())).param("op", nz(r.opponent())).param("ol", nz(r.opponentLawyer())).param("cr", nz(r.clientRole()))
                .param("st", status).param("oc", outcome).param("od", r.openedOn()).param("cd", "CLOSED".equals(status) && r.closedOn() == null ? LocalDate.now() : r.closedOn())
                .param("de", nz(r.description())).param("fee", r.agreedFeeMinor() == null ? 0 : r.agreedFeeMinor()).param("as", r.assignedTo()).query(UUID.class).single();
        linkClients(tenantId, id, r.clientIds());
        audit.record(actor, tenantId, "LEGAL_CASE_CREATED", "legal_case", id, null);
        return caseDetail(tenantId, id);
    }

    @Transactional
    public Map<String, Object> updateCase(UUID tenantId, UUID actor, UUID id, CaseReq r) {
        checkCase(r);
        requireCase(tenantId, id);
        String status = r.status() == null ? "OPEN" : r.status();
        jdbc.sql("""
                UPDATE legal.cases SET title = :ti, case_type = :ty, court = :co, circuit = :ci, court_case_no = :cn, opponent = :op, opponent_lawyer = :ol, client_role = :cr,
                  status = :st, outcome = :oc, opened_on = coalesce(:od, opened_on), closed_on = CASE WHEN :st = 'CLOSED' THEN coalesce(CAST(:cd AS date), closed_on, CURRENT_DATE) ELSE CAST(:cd AS date) END,
                  description = :de, agreed_fee_minor = :fee, assigned_to = :as, updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("ti", r.title().trim()).param("ty", r.caseType() == null ? "CIVIL" : r.caseType()).param("co", nz(r.court())).param("ci", nz(r.circuit())).param("cn", nz(r.courtCaseNo()))
                .param("op", nz(r.opponent())).param("ol", nz(r.opponentLawyer())).param("cr", nz(r.clientRole())).param("st", status).param("oc", r.outcome() == null ? "NONE" : r.outcome())
                .param("od", r.openedOn()).param("cd", r.closedOn()).param("de", nz(r.description())).param("fee", r.agreedFeeMinor() == null ? 0 : r.agreedFeeMinor()).param("as", r.assignedTo())
                .param("i", id).param("t", tenantId).update();
        if (r.clientIds() != null) {
            jdbc.sql("DELETE FROM legal.case_clients WHERE case_id = :k").param("k", id).update();
            linkClients(tenantId, id, r.clientIds());
        }
        audit.record(actor, tenantId, "LEGAL_CASE_UPDATED", "legal_case", id, null);
        return caseDetail(tenantId, id);
    }

    public record StatusReq(String status, String outcome) {}

    /** One tap from the list: status and/or result. */
    @Transactional
    public Map<String, Object> setStatus(UUID tenantId, UUID actor, UUID id, StatusReq r) {
        requireCase(tenantId, id);
        var fe = new FieldErrors();
        fe.when(r.status() != null && !STATUSES.contains(r.status()), "status", "Choose the status");
        fe.when(r.outcome() != null && !OUTCOMES.contains(r.outcome()), "outcome", "Choose the result");
        fe.throwIfAny();
        jdbc.sql("""
                UPDATE legal.cases SET status = coalesce(:s, status), outcome = coalesce(:o, outcome),
                  closed_on = CASE WHEN coalesce(:s, status) = 'CLOSED' THEN coalesce(closed_on, CURRENT_DATE) ELSE NULL END, updated_at = now() WHERE id = :i AND tenant_id = :t
                """).param("s", r.status()).param("o", r.outcome()).param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "LEGAL_CASE_STATUS", "legal_case", id, "{\"status\":\"" + r.status() + "\",\"outcome\":\"" + r.outcome() + "\"}");
        return caseDetail(tenantId, id);
    }

    @Transactional
    public void deleteCase(UUID tenantId, UUID actor, UUID id) {
        requireCase(tenantId, id);
        jdbc.sql("DELETE FROM legal.cases WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "LEGAL_CASE_DELETED", "legal_case", id, null);
    }

    private void linkClients(UUID tenantId, UUID caseId, List<UUID> ids) {
        if (ids == null) return;
        for (UUID c : new java.util.LinkedHashSet<>(ids)) {
            if (jdbc.sql("SELECT count(*) FROM legal.clients WHERE id = :c AND tenant_id = :t").param("c", c).param("t", tenantId).query(Long.class).single() == 0)
                throw BusinessException.badRequest("VALIDATION_ERROR", "One of the clients was not found").field("clientIds");
            jdbc.sql("INSERT INTO legal.case_clients (case_id, client_id) VALUES (:k, :c) ON CONFLICT DO NOTHING").param("k", caseId).param("c", c).update();
        }
    }

    void requireCase(UUID tenantId, UUID id) {
        if (id == null || jdbc.sql("SELECT count(*) FROM legal.cases WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Case not found");
    }

    // ---- actions, steps, payments, documents ---------------------------------------------------------------------

    public record ActionReq(LocalDate actionDate, String title, String notes, Long feeMinor) {}

    @Transactional
    public Map<String, Object> addAction(UUID tenantId, UUID actor, UUID caseId, ActionReq r) {
        requireCase(tenantId, caseId);
        var fe = new FieldErrors();
        fe.when(r.title() == null || r.title().isBlank(), "title", "Describe what was done");
        fe.when(r.feeMinor() != null && r.feeMinor() < 0, "feeMinor", "The fee cannot be negative");
        fe.throwIfAny();
        jdbc.sql("INSERT INTO legal.case_actions (tenant_id, case_id, action_date, title, notes, fee_minor, created_by) VALUES (:t, :k, coalesce(:d, CURRENT_DATE), :ti, :n, :f, :u)")
                .param("t", tenantId).param("k", caseId).param("d", r.actionDate()).param("ti", r.title().trim()).param("n", nz(r.notes())).param("f", r.feeMinor() == null ? 0 : r.feeMinor()).param("u", actor).update();
        touch(caseId);
        audit.record(actor, tenantId, "LEGAL_ACTION_ADDED", "legal_case", caseId, null);
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> updateAction(UUID tenantId, UUID actor, UUID caseId, UUID id, ActionReq r) {
        requireCase(tenantId, caseId);
        var fe = new FieldErrors();
        fe.when(r.title() == null || r.title().isBlank(), "title", "Describe what was done");
        fe.when(r.feeMinor() != null && r.feeMinor() < 0, "feeMinor", "The fee cannot be negative");
        fe.throwIfAny();
        jdbc.sql("UPDATE legal.case_actions SET action_date = coalesce(:d, action_date), title = :ti, notes = :n, fee_minor = :f WHERE id = :i AND case_id = :k AND tenant_id = :t")
                .param("d", r.actionDate()).param("ti", r.title().trim()).param("n", nz(r.notes())).param("f", r.feeMinor() == null ? 0 : r.feeMinor()).param("i", id).param("k", caseId).param("t", tenantId).update();
        touch(caseId);
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> deleteAction(UUID tenantId, UUID caseId, UUID id) {
        requireCase(tenantId, caseId);
        jdbc.sql("DELETE FROM legal.case_actions WHERE id = :i AND case_id = :k AND tenant_id = :t").param("i", id).param("k", caseId).param("t", tenantId).update();
        return caseDetail(tenantId, caseId);
    }

    public record StepReq(String kind, Instant startsAt, Integer durationMinutes, String description, String location, Boolean done, String result) {}

    private void checkStep(StepReq r) {
        var fe = new FieldErrors();
        fe.when(r.startsAt() == null, "startsAt", "Choose the date and time");
        fe.when(r.description() == null || r.description().isBlank(), "description", "Describe this step");
        fe.when(r.kind() != null && !STEP_KINDS.contains(r.kind()), "kind", "Choose the kind of step");
        fe.when(r.durationMinutes() != null && (r.durationMinutes() < 5 || r.durationMinutes() > 1440), "durationMinutes", "Use 5 to 1440 minutes");
        fe.throwIfAny();
    }

    @Transactional
    public Map<String, Object> addStep(UUID tenantId, UUID actor, UUID caseId, StepReq r) {
        requireCase(tenantId, caseId);
        checkStep(r);
        jdbc.sql("""
                INSERT INTO legal.case_steps (tenant_id, case_id, kind, starts_at, duration_minutes, description, location, done, result)
                VALUES (:t, :k, :ki, :s, :du, :d, :l, :dn, :r)
                """).param("t", tenantId).param("k", caseId).param("ki", r.kind() == null ? "HEARING" : r.kind()).param("s", java.sql.Timestamp.from(r.startsAt()))
                .param("du", r.durationMinutes() == null ? 60 : r.durationMinutes()).param("d", r.description().trim()).param("l", nz(r.location())).param("dn", Boolean.TRUE.equals(r.done())).param("r", nz(r.result())).update();
        touch(caseId);
        audit.record(actor, tenantId, "LEGAL_STEP_ADDED", "legal_case", caseId, null);
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> updateStep(UUID tenantId, UUID actor, UUID caseId, UUID id, StepReq r) {
        requireCase(tenantId, caseId);
        checkStep(r);
        // a moved step reminds again
        jdbc.sql("""
                UPDATE legal.case_steps SET kind = :ki, duration_minutes = :du, description = :d, location = :l, done = :dn, result = :r,
                  reminder_sent_at = CASE WHEN starts_at <> :s THEN NULL ELSE reminder_sent_at END, early_reminder_sent_at = CASE WHEN starts_at <> :s THEN NULL ELSE early_reminder_sent_at END,
                  starts_at = :s WHERE id = :i AND case_id = :k AND tenant_id = :t
                """).param("ki", r.kind() == null ? "HEARING" : r.kind()).param("s", java.sql.Timestamp.from(r.startsAt())).param("du", r.durationMinutes() == null ? 60 : r.durationMinutes())
                .param("d", r.description().trim()).param("l", nz(r.location())).param("dn", Boolean.TRUE.equals(r.done())).param("r", nz(r.result())).param("i", id).param("k", caseId).param("t", tenantId).update();
        touch(caseId);
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> deleteStep(UUID tenantId, UUID caseId, UUID id) {
        requireCase(tenantId, caseId);
        jdbc.sql("DELETE FROM legal.case_steps WHERE id = :i AND case_id = :k AND tenant_id = :t").param("i", id).param("k", caseId).param("t", tenantId).update();
        return caseDetail(tenantId, caseId);
    }

    public record PaymentReq(Long amountMinor, LocalDate paidOn, String method, String note, UUID clientId) {}

    @Transactional
    public Map<String, Object> addPayment(UUID tenantId, UUID actor, UUID caseId, PaymentReq r) {
        requireCase(tenantId, caseId);
        var fe = new FieldErrors();
        fe.when(r.amountMinor() == null || r.amountMinor() <= 0, "amountMinor", "Enter the amount received");
        fe.when(r.method() != null && !Set.of("CASH", "TRANSFER", "INSTAPAY", "WALLET", "CHEQUE", "OTHER").contains(r.method()), "method", "Choose how it was paid");
        fe.throwIfAny();
        jdbc.sql("INSERT INTO legal.payments (tenant_id, case_id, client_id, amount_minor, paid_on, method, note) VALUES (:t, :k, :c, :a, coalesce(:d, CURRENT_DATE), :m, :n)")
                .param("t", tenantId).param("k", caseId).param("c", r.clientId()).param("a", r.amountMinor()).param("d", r.paidOn()).param("m", r.method() == null ? "CASH" : r.method()).param("n", nz(r.note())).update();
        touch(caseId);
        audit.record(actor, tenantId, "LEGAL_PAYMENT_ADDED", "legal_case", caseId, "{\"amount\":" + r.amountMinor() + "}");
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> deletePayment(UUID tenantId, UUID actor, UUID caseId, UUID id) {
        requireCase(tenantId, caseId);
        jdbc.sql("DELETE FROM legal.payments WHERE id = :i AND case_id = :k AND tenant_id = :t").param("i", id).param("k", caseId).param("t", tenantId).update();
        audit.record(actor, tenantId, "LEGAL_PAYMENT_DELETED", "legal_case", caseId, null);
        return caseDetail(tenantId, caseId);
    }

    public record DocumentReq(UUID fileId, String name) {}

    @Transactional
    public Map<String, Object> addDocument(UUID tenantId, UUID actor, UUID caseId, DocumentReq r) {
        requireCase(tenantId, caseId);
        if (r.fileId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Upload the file first").field("fileId");
        files.requireReady(tenantId, r.fileId(), Set.of("LEGAL_DOCUMENT"), null);
        String type = jdbc.sql("SELECT content_type FROM core.files WHERE id = :f").param("f", r.fileId()).query(String.class).optional().orElse("");
        String name = r.name() == null || r.name().isBlank() ? jdbc.sql("SELECT original_filename FROM core.files WHERE id = :f").param("f", r.fileId()).query(String.class).optional().orElse("document") : r.name().trim();
        jdbc.sql("INSERT INTO legal.case_documents (tenant_id, case_id, file_id, url, name, content_type) VALUES (:t, :k, :f, :u, :n, :c)")
                .param("t", tenantId).param("k", caseId).param("f", r.fileId()).param("u", "/api/v1/files/" + r.fileId() + "/content").param("n", name).param("c", type).update();
        audit.record(actor, tenantId, "LEGAL_DOCUMENT_ADDED", "legal_case", caseId, null);
        return caseDetail(tenantId, caseId);
    }

    @Transactional
    public Map<String, Object> deleteDocument(UUID tenantId, UUID caseId, UUID id) {
        requireCase(tenantId, caseId);
        jdbc.sql("DELETE FROM legal.case_documents WHERE id = :i AND case_id = :k AND tenant_id = :t").param("i", id).param("k", caseId).param("t", tenantId).update();
        return caseDetail(tenantId, caseId);
    }

    private void touch(UUID caseId) { jdbc.sql("UPDATE legal.cases SET updated_at = now() WHERE id = :k").param("k", caseId).update(); }

    // ---- finance -----------------------------------------------------------------------------------------------

    /** Billed (agreed fees + action fees), collected and due; income per month over the range; the clients who owe most. */
    public Map<String, Object> finance(UUID tenantId, LocalDate from, LocalDate to) {
        DateRange.check(from, to);
        LocalDate f = from == null ? LocalDate.now().minusMonths(11).withDayOfMonth(1) : from, t = to == null ? LocalDate.now() : to;
        Map<String, Object> out = new LinkedHashMap<>();
        var totals = jdbc.sql("""
                SELECT coalesce((SELECT sum(agreed_fee_minor) FROM legal.cases WHERE tenant_id = :t), 0) + coalesce((SELECT sum(fee_minor) FROM legal.case_actions WHERE tenant_id = :t), 0) AS billed,
                       coalesce((SELECT sum(amount_minor) FROM legal.payments WHERE tenant_id = :t), 0) AS collected,
                       coalesce((SELECT sum(amount_minor) FROM legal.payments WHERE tenant_id = :t AND paid_on BETWEEN :f AND :to), 0) AS collected_in_range,
                       coalesce((SELECT sum(fee_minor) FROM legal.case_actions WHERE tenant_id = :t AND action_date BETWEEN :f AND :to), 0)
                         + coalesce((SELECT sum(agreed_fee_minor) FROM legal.cases WHERE tenant_id = :t AND opened_on BETWEEN :f AND :to), 0) AS billed_in_range
                """).param("t", tenantId).param("f", f).param("to", t).query().singleRow();
        out.put("billedMinor", totals.get("billed"));
        out.put("collectedMinor", totals.get("collected"));
        out.put("dueMinor", ((Number) totals.get("billed")).longValue() - ((Number) totals.get("collected")).longValue());
        out.put("collectedInRangeMinor", totals.get("collected_in_range"));
        out.put("billedInRangeMinor", totals.get("billed_in_range"));
        out.put("from", f);
        out.put("to", t);
        out.put("monthly", Rows.camel(jdbc.sql("""
                SELECT to_char(m, 'YYYY-MM') AS month,
                  coalesce((SELECT sum(amount_minor) FROM legal.payments WHERE tenant_id = :t AND date_trunc('month', paid_on) = m), 0) AS collected_minor,
                  coalesce((SELECT sum(fee_minor) FROM legal.case_actions WHERE tenant_id = :t AND date_trunc('month', action_date) = m), 0)
                    + coalesce((SELECT sum(agreed_fee_minor) FROM legal.cases WHERE tenant_id = :t AND date_trunc('month', opened_on) = m), 0) AS billed_minor
                FROM generate_series(date_trunc('month', CAST(:f AS date)), date_trunc('month', CAST(:to AS date)), interval '1 month') AS m ORDER BY m
                """).param("t", tenantId).param("f", f).param("to", t).query().listOfRows()));
        out.put("topDue", Rows.camel(jdbc.sql("""
                SELECT k.id, k.number, k.title,
                  (SELECT string_agg(c.name, '، ') FROM legal.case_clients cc JOIN legal.clients c ON c.id = cc.client_id WHERE cc.case_id = k.id) AS client_names,
                  k.agreed_fee_minor + (SELECT coalesce(sum(a.fee_minor), 0) FROM legal.case_actions a WHERE a.case_id = k.id) - (SELECT coalesce(sum(p.amount_minor), 0) FROM legal.payments p WHERE p.case_id = k.id) AS due_minor
                FROM legal.cases k WHERE k.tenant_id = :t ORDER BY due_minor DESC LIMIT 10
                """).param("t", tenantId).query().listOfRows()).stream().filter(r -> ((Number) r.get("dueMinor")).longValue() > 0).toList());
        out.put("recentPayments", Rows.camel(jdbc.sql("""
                SELECT p.id, p.amount_minor, p.paid_on, p.method, p.note, k.id AS case_id, k.number, k.title, c.name AS client_name
                FROM legal.payments p JOIN legal.cases k ON k.id = p.case_id LEFT JOIN legal.clients c ON c.id = p.client_id
                WHERE p.tenant_id = :t AND p.paid_on BETWEEN :f AND :to ORDER BY p.paid_on DESC, p.created_at DESC LIMIT 30
                """).param("t", tenantId).param("f", f).param("to", t).query().listOfRows()));
        return out;
    }

    /** Dashboard home numbers. */
    public Map<String, Object> overview(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT (SELECT count(*) FROM legal.cases WHERE tenant_id = :t AND status = 'OPEN') AS open_cases,
                       (SELECT count(*) FROM legal.cases WHERE tenant_id = :t AND status = 'UNDER_STUDY') AS under_study,
                       (SELECT count(*) FROM legal.cases WHERE tenant_id = :t AND outcome = 'WON') AS won,
                       (SELECT count(*) FROM legal.cases WHERE tenant_id = :t AND outcome = 'LOST') AS lost,
                       (SELECT count(*) FROM legal.clients WHERE tenant_id = :t) AS clients,
                       (SELECT count(*) FROM legal.bookings WHERE tenant_id = :t AND status = 'PENDING' AND starts_at > now()) AS pending_bookings,
                       (SELECT count(*) FROM legal.bookings b WHERE b.tenant_id = :t AND b.status = 'APPROVED' AND (b.starts_at AT TIME ZONE coalesce((SELECT timezone FROM core.tenants WHERE id = :t), 'Africa/Cairo'))::date = (now() AT TIME ZONE coalesce((SELECT timezone FROM core.tenants WHERE id = :t), 'Africa/Cairo'))::date) AS today_sessions,
                       (SELECT count(*) FROM legal.case_steps WHERE tenant_id = :t AND NOT done AND starts_at BETWEEN now() AND now() + interval '7 days') AS week_steps
                """).param("t", tenantId).query().singleRow();
        Map<String, Object> out = Rows.camel(r);
        long won = ((Number) r.get("won")).longValue(), lost = ((Number) r.get("lost")).longValue();
        out.put("winRate", won + lost == 0 ? null : Math.round(won * 100.0 / (won + lost)));
        var fin = finance(tenantId, null, null);
        out.put("dueMinor", fin.get("dueMinor"));
        out.put("collectedThisMonthMinor", jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM legal.payments WHERE tenant_id = :t AND date_trunc('month', paid_on) = date_trunc('month', CURRENT_DATE)")
                .param("t", tenantId).query(Long.class).single());
        out.put("upcoming", Rows.camel(jdbc.sql("""
                SELECT s.id, s.kind, s.starts_at, s.description, s.location, k.id AS case_id, k.number, k.title FROM legal.case_steps s JOIN legal.cases k ON k.id = s.case_id
                WHERE s.tenant_id = :t AND NOT s.done AND s.starts_at > now() - interval '2 hours' ORDER BY s.starts_at LIMIT 8
                """).param("t", tenantId).query().listOfRows()));
        out.put("casesByType", Rows.camel(jdbc.sql("SELECT case_type, count(*) AS n FROM legal.cases WHERE tenant_id = :t GROUP BY case_type ORDER BY n DESC").param("t", tenantId).query().listOfRows()));
        return out;
    }

    /** A printable fee statement for one client: every case, its fees, actions and payments. */
    public Map<String, Object> statement(UUID tenantId, UUID clientId) {
        Map<String, Object> c = client(tenantId, clientId);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object o : (List<?>) c.get("cases")) cases.add(caseDetail(tenantId, (UUID) ((Map<?, ?>) o).get("id")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client", c);
        out.put("cases", cases);
        out.put("generatedAt", Instant.now());
        return out;
    }

    // ---- deep search ---------------------------------------------------------------------------------------------

    /** One box for everything: clients, cases (number, court no., opponent, court), actions, steps, notes and documents. Arabic letter forms are folded. */
    public Map<String, Object> search(UUID tenantId, String q) {
        if (q == null || q.trim().length() < 2) return Map.of("clients", List.of(), "cases", List.of(), "actions", List.of(), "steps", List.of(), "notes", List.of(), "documents", List.of());
        String n = "%" + fold(q.trim()) + "%";
        String f = "translate(lower(%s), 'أإآةىؤئ', 'اااهيوي')";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clients", Rows.camel(jdbc.sql("SELECT id, name, phone, email FROM legal.clients WHERE tenant_id = :t AND (" + f.formatted("name") + " LIKE :q OR phone LIKE :q OR national_id LIKE :q OR " + f.formatted("notes") + " LIKE :q) ORDER BY name LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        out.put("cases", Rows.camel(jdbc.sql("SELECT id, number, title, status, outcome, court, court_case_no FROM legal.cases WHERE tenant_id = :t AND (" + f.formatted("title") + " LIKE :q OR number LIKE :q OR court_case_no LIKE :q OR "
                        + f.formatted("opponent") + " LIKE :q OR " + f.formatted("court") + " LIKE :q OR " + f.formatted("description") + " LIKE :q) ORDER BY opened_on DESC LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        out.put("actions", Rows.camel(jdbc.sql("SELECT a.id, a.title, a.action_date, k.id AS case_id, k.number, k.title AS case_title FROM legal.case_actions a JOIN legal.cases k ON k.id = a.case_id WHERE a.tenant_id = :t AND ("
                        + f.formatted("a.title") + " LIKE :q OR " + f.formatted("a.notes") + " LIKE :q) ORDER BY a.action_date DESC LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        out.put("steps", Rows.camel(jdbc.sql("SELECT s.id, s.kind, s.starts_at, s.description, k.id AS case_id, k.number FROM legal.case_steps s JOIN legal.cases k ON k.id = s.case_id WHERE s.tenant_id = :t AND ("
                        + f.formatted("s.description") + " LIKE :q OR " + f.formatted("s.location") + " LIKE :q OR " + f.formatted("s.result") + " LIKE :q) ORDER BY s.starts_at DESC LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        out.put("notes", Rows.camel(jdbc.sql("SELECT id, title, starts_at, case_id FROM legal.calendar_notes WHERE tenant_id = :t AND (" + f.formatted("title") + " LIKE :q OR " + f.formatted("body") + " LIKE :q) ORDER BY starts_at DESC LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        out.put("documents", Rows.camel(jdbc.sql("SELECT d.id, d.name, d.url, k.id AS case_id, k.number FROM legal.case_documents d JOIN legal.cases k ON k.id = d.case_id WHERE d.tenant_id = :t AND " + f.formatted("d.name") + " LIKE :q ORDER BY d.created_at DESC LIMIT 10")
                .param("t", tenantId).param("q", n).query().listOfRows()));
        return out;
    }

    static String fold(String s) {
        String t = PhoneNormalizer.latinDigits(s).toLowerCase().replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        return t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي');
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String like(String q) { return q == null || q.isBlank() ? null : "%" + q.trim() + "%"; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static List<String> csv(String s) { return s == null || s.isBlank() ? List.of() : java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(String::toUpperCase).toList(); }
    private static String tags(List<String> t) { return t == null ? "" : String.join(",", t.stream().map(String::trim).filter(x -> !x.isEmpty() && !x.contains(",")).limit(10).toList()); }

    private static List<String> arr(Object o) {
        if (o instanceof java.sql.Array a) try { return List.of((String[]) a.getArray()); } catch (java.sql.SQLException e) { return List.of(); }
        return List.of();
    }
}

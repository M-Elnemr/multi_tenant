package com.platform.medical;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encounters, notes, prescriptions, labs, documents and the patient timeline. Clinical records are
 * append-oriented: edits keep revisions, cancellations keep the record (spec 2.7 / 75). Every
 * access to a record is audited, whether by staff or by the patient.
 */
@Service
public class ClinicalService {
    public record EncounterReq(UUID patientId, UUID appointmentId, String chiefComplaint) {}
    public record EncounterPatch(String chiefComplaint, String clinicalSummary, LocalDate followUpDate) {}
    public record VitalsReq(BigDecimal heightCm, BigDecimal weightKg, BigDecimal temperatureC, Integer heartRateBpm, Integer respiratoryRate,
                            Integer systolicBp, Integer diastolicBp, BigDecimal oxygenSaturation, String notes) {}
    public record ConditionReq(String name, String codeSystem, String code, String status, LocalDate onsetDate, String notes) {}
    public record NoteReq(String noteType, String content, boolean patientVisible) {}
    public record ItemReq(String medicationName, String genericName, String strength, String dosage, String route, String frequency, String duration, String quantity, String instructions) {}
    public record PrescriptionReq(List<ItemReq> items, String notes, boolean issue, UUID imageFileId) {}
    public record LabOrderReq(String testName, String instructions, String priority, LocalDate dueDate) {}
    public record LabResultReq(String resultText, String resultSummary, UUID fileId, boolean patientVisible) {}
    public record DocumentReq(String documentType, String title, String description, UUID fileId, boolean patientVisible) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PatientService patients;
    private final ApplicationEventPublisher events;
    private final com.platform.files.FileService files;

    public ClinicalService(JdbcClient jdbc, AuditService audit, PatientService patients, ApplicationEventPublisher events, com.platform.files.FileService files) {
        this.files = files;
        this.jdbc = jdbc;
        this.audit = audit;
        this.patients = patients;
        this.events = events;
    }

    // ---- encounters -----------------------------------------------------------------------------------------------

    UUID doctorId(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT id FROM medical.doctors WHERE tenant_id = :t AND user_id = :u AND is_active").param("t", tenantId).param("u", userId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.forbidden("NOT_A_DOCTOR", "Only a doctor of this clinic can do this"));
    }

    @Transactional
    public Map<String, Object> createEncounter(UUID tenantId, UUID actor, EncounterReq r) {
        UUID doctor = doctorId(tenantId, actor);
        patients.summary(tenantId, r.patientId());
        UUID branch = null;
        if (r.appointmentId() != null) {
            var a = jdbc.sql("SELECT patient_id, branch_id, status FROM medical.appointments WHERE id = :a AND tenant_id = :t").param("a", r.appointmentId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Appointment not found"));
            if (!r.patientId().equals(a.get("patient_id"))) throw BusinessException.badRequest("APPOINTMENT_PATIENT_MISMATCH", "Appointment belongs to a different patient");
            if (!Set.of("CONFIRMED", "CHECKED_IN", "IN_PROGRESS").contains((String) a.get("status")))
                throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "The appointment is not ready for a visit");
            branch = (UUID) a.get("branch_id");
        }
        UUID id = jdbc.sql("INSERT INTO medical.encounters (tenant_id, patient_id, doctor_id, appointment_id, branch_id, chief_complaint) VALUES (:t, :p, :d, :a, :b, :c) RETURNING id")
                .param("t", tenantId).param("p", r.patientId()).param("d", doctor).param("a", r.appointmentId()).param("b", branch).param("c", r.chiefComplaint()).query(UUID.class).single();
        audit.record(actor, tenantId, "ENCOUNTER_CREATED", "encounter", id, null);
        return encounter(tenantId, actor, id);
    }

    @Transactional
    public Map<String, Object> updateEncounter(UUID tenantId, UUID actor, UUID id, EncounterPatch p) {
        requireEncounter(tenantId, id);
        jdbc.sql("UPDATE medical.encounters SET chief_complaint = coalesce(:c, chief_complaint), clinical_summary = coalesce(:s, clinical_summary), follow_up_date = coalesce(:f, follow_up_date), updated_at = now() WHERE id = :i AND tenant_id = :t")
                .param("c", p.chiefComplaint()).param("s", p.clinicalSummary()).param("f", p.followUpDate() == null ? null : java.sql.Date.valueOf(p.followUpDate())).param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "ENCOUNTER_UPDATED", "encounter", id, null);
        return encounter(tenantId, actor, id);
    }

    public Map<String, Object> encounter(UUID tenantId, UUID actor, UUID id) {
        var e = requireEncounter(tenantId, id);
        Map<String, Object> out = Rows.camel(e);
        out.put("vitals", Rows.camel(jdbc.sql("SELECT id, height_cm, weight_kg, temperature_c, heart_rate_bpm, respiratory_rate, systolic_bp, diastolic_bp, oxygen_saturation, notes, created_at FROM medical.vitals WHERE encounter_id = :e AND tenant_id = :t ORDER BY created_at").param("e", id).param("t", tenantId).query().listOfRows()));
        out.put("notes", Rows.camel(jdbc.sql("SELECT id, note_type, content, is_patient_visible, author_user_id, created_at, updated_at FROM medical.notes WHERE encounter_id = :e AND tenant_id = :t ORDER BY created_at").param("e", id).param("t", tenantId).query().listOfRows()));
        out.put("conditions", Rows.camel(jdbc.sql("SELECT id, name, status, onset_date, notes FROM medical.conditions WHERE encounter_id = :e AND tenant_id = :t ORDER BY created_at").param("e", id).param("t", tenantId).query().listOfRows()));
        out.put("prescriptions", prescriptions(tenantId, "p.encounter_id = :x", id, false));
        out.put("labOrders", labOrders(tenantId, "o.encounter_id = :x", id, false));
        audit.record(actor, tenantId, "PATIENT_VIEWED", "patient", (UUID) e.get("patient_id"), "{\"via\":\"encounter\"}");
        return out;
    }

    private Map<String, Object> requireEncounter(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT id, patient_id, doctor_id, appointment_id, branch_id, visit_at, chief_complaint, clinical_summary, follow_up_date, created_at FROM medical.encounters WHERE id = :i AND tenant_id = :t")
                .param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Encounter not found"));
    }

    @Transactional
    public void addVitals(UUID tenantId, UUID actor, UUID encounterId, VitalsReq v) {
        requireEncounter(tenantId, encounterId);
        jdbc.sql("""
                INSERT INTO medical.vitals (tenant_id, encounter_id, height_cm, weight_kg, temperature_c, heart_rate_bpm, respiratory_rate, systolic_bp, diastolic_bp, oxygen_saturation, notes)
                VALUES (:t, :e, :h, :w, :tc, :hr, :rr, :s, :d, :o, :n)
                """).param("t", tenantId).param("e", encounterId).param("h", v.heightCm()).param("w", v.weightKg()).param("tc", v.temperatureC()).param("hr", v.heartRateBpm())
                .param("rr", v.respiratoryRate()).param("s", v.systolicBp()).param("d", v.diastolicBp()).param("o", v.oxygenSaturation()).param("n", v.notes()).update();
        audit.record(actor, tenantId, "VITALS_RECORDED", "encounter", encounterId, null);
    }

    @Transactional
    public void addCondition(UUID tenantId, UUID actor, UUID encounterId, ConditionReq c) {
        var e = requireEncounter(tenantId, encounterId);
        if (c.name() == null || c.name().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Condition name is required");
        jdbc.sql("INSERT INTO medical.conditions (tenant_id, patient_id, encounter_id, name, code_system, code, status, onset_date, notes) VALUES (:t, :p, :e, :n, :cs, :c, coalesce(:s,'ACTIVE'), :o, :no)")
                .param("t", tenantId).param("p", e.get("patient_id")).param("e", encounterId).param("n", c.name().trim()).param("cs", c.codeSystem()).param("c", c.code()).param("s", c.status())
                .param("o", c.onsetDate() == null ? null : java.sql.Date.valueOf(c.onsetDate())).param("no", c.notes()).update();
        audit.record(actor, tenantId, "CONDITION_RECORDED", "encounter", encounterId, null);
    }

    // ---- notes (revisioned) ---------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> addNote(UUID tenantId, UUID actor, UUID encounterId, NoteReq r) {
        var e = requireEncounter(tenantId, encounterId);
        if (r.content() == null || r.content().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Note content is required");
        String type = r.noteType() == null ? "CLINICAL" : r.noteType();
        if (!Set.of("CLINICAL", "PROGRESS", "INSTRUCTION", "ADMINISTRATIVE").contains(type)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid note type");
        UUID id = jdbc.sql("INSERT INTO medical.notes (tenant_id, patient_id, encounter_id, author_user_id, note_type, content, is_patient_visible) VALUES (:t, :p, :e, :a, :ty, :c, :v) RETURNING id")
                .param("t", tenantId).param("p", e.get("patient_id")).param("e", encounterId).param("a", actor).param("ty", type).param("c", r.content()).param("v", r.patientVisible()).query(UUID.class).single();
        audit.record(actor, tenantId, "NOTE_CREATED", "note", id, null);
        return Map.of("id", id);
    }

    /** Editing keeps the previous text in note_revisions; only the author may edit. */
    @Transactional
    public Map<String, Object> updateNote(UUID tenantId, UUID actor, UUID noteId, String content, Boolean patientVisible) {
        var n = jdbc.sql("SELECT content, is_patient_visible, author_user_id FROM medical.notes WHERE id = :n AND tenant_id = :t FOR UPDATE").param("n", noteId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Note not found"));
        if (!actor.equals(n.get("author_user_id"))) throw BusinessException.forbidden("FORBIDDEN", "Only the author can edit a clinical note");
        jdbc.sql("INSERT INTO medical.note_revisions (tenant_id, note_id, content, was_patient_visible, changed_by) VALUES (:t, :n, :c, :v, :u)")
                .param("t", tenantId).param("n", noteId).param("c", n.get("content")).param("v", n.get("is_patient_visible")).param("u", actor).update();
        jdbc.sql("UPDATE medical.notes SET content = coalesce(:c, content), is_patient_visible = coalesce(:v, is_patient_visible), updated_at = now() WHERE id = :n AND tenant_id = :t")
                .param("c", content).param("v", patientVisible).param("n", noteId).param("t", tenantId).update();
        audit.record(actor, tenantId, "NOTE_UPDATED", "note", noteId, null);
        return Map.of("id", noteId);
    }

    // ---- prescriptions -----------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createPrescription(UUID tenantId, UUID actor, UUID encounterId, PrescriptionReq r) {
        UUID doctor = doctorId(tenantId, actor);
        var e = requireEncounter(tenantId, encounterId);
        boolean typed = r.items() != null && !r.items().isEmpty();
        if (!typed && r.imageFileId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "A prescription needs at least one item or a photo of the prescription");
        if (r.imageFileId() != null) {
            // the photo/scan of the paper prescription: an uploaded, finished, private PRESCRIPTION file of this clinic, and a picture (not a PDF), so it can be printed and shown
            files.requireReady(tenantId, r.imageFileId(), Set.of("PRESCRIPTION"), null);
            String ct = jdbc.sql("SELECT content_type FROM core.files WHERE id = :i AND tenant_id = :t").param("i", r.imageFileId()).param("t", tenantId).query(String.class).single();
            if (ct == null || !Set.of("image/jpeg", "image/png").contains(ct.toLowerCase())) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "The prescription photo must be a JPEG or PNG picture");
        }
        UUID id = jdbc.sql("INSERT INTO medical.prescriptions (tenant_id, patient_id, encounter_id, prescribed_by, status, issued_at, notes, image_file_id) VALUES (:t, :p, :e, :d, :s, :i, :n, :img) RETURNING id")
                .param("t", tenantId).param("p", e.get("patient_id")).param("e", encounterId).param("d", doctor).param("s", r.issue() ? "ISSUED" : "DRAFT")
                .param("i", r.issue() ? java.sql.Timestamp.from(Instant.now()) : null).param("n", r.notes()).param("img", r.imageFileId()).query(UUID.class).single();
        int i = 0;
        for (ItemReq it : typed ? r.items() : List.<ItemReq>of()) {
            if (it.medicationName() == null || it.medicationName().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Medication name is required");
            jdbc.sql("INSERT INTO medical.prescription_items (prescription_id, medication_name, generic_name, strength, dosage, route, frequency, duration, quantity, instructions, sort_order) VALUES (:p,:m,:g,:s,:d,:r,:f,:du,:q,:in,:o)")
                    .param("p", id).param("m", it.medicationName().trim()).param("g", it.genericName()).param("s", it.strength()).param("d", it.dosage()).param("r", it.route())
                    .param("f", it.frequency()).param("du", it.duration()).param("q", it.quantity()).param("in", it.instructions()).param("o", i++).update();
        }
        revision(tenantId, id, r.issue() ? "ISSUED" : "CREATED", actor);
        audit.record(actor, tenantId, "PRESCRIPTION_CREATED", "prescription", id, null);
        if (r.issue()) events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, (UUID) e.get("patient_id"), "PRESCRIPTION_ISSUED"));
        return prescriptions(tenantId, "p.id = :x", id, false).get(0);
    }

    @Transactional
    public Map<String, Object> setPrescriptionStatus(UUID tenantId, UUID actor, UUID id, String to) {
        var p = jdbc.sql("SELECT status, patient_id FROM medical.prescriptions WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Prescription not found"));
        String from = (String) p.get("status");
        boolean ok = ("ISSUED".equals(to) && "DRAFT".equals(from)) || ("CANCELLED".equals(to) && Set.of("DRAFT", "ISSUED").contains(from));
        if (!ok) throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "Cannot move a prescription from " + from + " to " + to);
        jdbc.sql("UPDATE medical.prescriptions SET status = :s, issued_at = CASE WHEN :s = 'ISSUED' THEN now() ELSE issued_at END, updated_at = now() WHERE id = :i").param("s", to).param("i", id).update();
        revision(tenantId, id, to, actor);
        audit.record(actor, tenantId, "ISSUED".equals(to) ? "PRESCRIPTION_CREATED" : "PRESCRIPTION_CANCELLED", "prescription", id, null);
        if ("ISSUED".equals(to)) events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, (UUID) p.get("patient_id"), "PRESCRIPTION_ISSUED"));
        return prescriptions(tenantId, "p.id = :x", id, false).get(0);
    }

    private void revision(UUID tenantId, UUID prescriptionId, String event, UUID actor) {
        jdbc.sql("""
                INSERT INTO medical.prescription_revisions (tenant_id, prescription_id, event, snapshot, changed_by)
                SELECT :t, p.id, :e, jsonb_build_object('status', p.status, 'notes', p.notes, 'items',
                    coalesce((SELECT jsonb_agg(to_jsonb(i) - 'id' - 'prescription_id' ORDER BY i.sort_order) FROM medical.prescription_items i WHERE i.prescription_id = p.id), '[]'::jsonb)), :u
                FROM medical.prescriptions p WHERE p.id = :p
                """).param("t", tenantId).param("e", event).param("u", actor).param("p", prescriptionId).update();
    }

    private List<Map<String, Object>> prescriptions(UUID tenantId, String cond, Object arg, boolean patientView) {
        var rows = jdbc.sql("SELECT p.id, p.encounter_id, p.status, p.issued_at, p.notes, p.image_file_id, d.display_name AS doctor_name, p.created_at FROM medical.prescriptions p JOIN medical.doctors d ON d.id = p.prescribed_by WHERE p.tenant_id = :t AND " + cond
                + (patientView ? " AND p.status = 'ISSUED'" : "") + " ORDER BY coalesce(p.issued_at, p.created_at) DESC").param("t", tenantId).param("x", arg).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = Rows.camel(r);
            m.put("items", Rows.camel(jdbc.sql("SELECT medication_name, generic_name, strength, dosage, route, frequency, duration, quantity, instructions FROM medical.prescription_items WHERE prescription_id = :p ORDER BY sort_order").param("p", r.get("id")).query().listOfRows()));
            out.add(m);
        }
        return out;
    }

    // ---- labs ------------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createLabOrder(UUID tenantId, UUID actor, UUID encounterId, LabOrderReq r) {
        UUID doctor = doctorId(tenantId, actor);
        var e = requireEncounter(tenantId, encounterId);
        if (r.testName() == null || r.testName().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Test name is required");
        if (r.priority() != null && !Set.of("ROUTINE", "URGENT").contains(r.priority())) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid priority");
        UUID id = jdbc.sql("INSERT INTO medical.lab_orders (tenant_id, patient_id, encounter_id, ordered_by, test_name, instructions, priority, due_date) VALUES (:t,:p,:e,:d,:n,:i,coalesce(:pr,'ROUTINE'),:du) RETURNING id")
                .param("t", tenantId).param("p", e.get("patient_id")).param("e", encounterId).param("d", doctor).param("n", r.testName().trim()).param("i", r.instructions())
                .param("pr", r.priority()).param("du", r.dueDate() == null ? null : java.sql.Date.valueOf(r.dueDate())).query(UUID.class).single();
        audit.record(actor, tenantId, "LAB_ORDER_CREATED", "lab_order", id, null);
        return labOrders(tenantId, "o.id = :x", id, false).get(0);
    }

    @Transactional
    public Map<String, Object> addLabResult(UUID tenantId, UUID actor, UUID labOrderId, LabResultReq r) {
        var o = lockLab(tenantId, labOrderId);
        addResult(tenantId, actor, labOrderId, r, false);
        jdbc.sql("UPDATE medical.lab_orders SET status = CASE WHEN status IN ('ORDERED','PATIENT_UPLOADED') THEN 'UNDER_REVIEW' ELSE status END, updated_at = now() WHERE id = :i").param("i", labOrderId).update();
        audit.record(actor, tenantId, "LAB_RESULT_UPLOADED", "lab_order", labOrderId, null);
        events.publishEvent(new MedicalEvents.PatientUpdate(tenantId, (UUID) o.get("patient_id"), "LAB_RESULT_UPLOADED"));
        return labOrders(tenantId, "o.id = :x", labOrderId, false).get(0);
    }

    /** The patient (or guardian) uploads a result for an order placed for them. */
    @Transactional
    public Map<String, Object> addLabResultByPatient(UUID tenantId, UUID userId, UUID labOrderId, LabResultReq r) {
        var o = lockLab(tenantId, labOrderId);
        patients.requireAccessible(tenantId, userId, (UUID) o.get("patient_id"));
        if ("CANCELLED".equals(o.get("status")) || "REVIEWED".equals(o.get("status"))) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This lab request is closed");
        addResult(tenantId, userId, labOrderId, new LabResultReq(r.resultText(), r.resultSummary(), r.fileId(), true), true);
        jdbc.sql("UPDATE medical.lab_orders SET status = CASE WHEN status = 'ORDERED' THEN 'PATIENT_UPLOADED' ELSE status END, updated_at = now() WHERE id = :i").param("i", labOrderId).update();
        audit.record(userId, tenantId, "LAB_RESULT_UPLOADED", "lab_order", labOrderId, "{\"by\":\"patient\"}");
        return Map.of("labOrderId", labOrderId, "status", "UPLOADED");
    }

    private void addResult(UUID tenantId, UUID by, UUID labOrderId, LabResultReq r, boolean byPatient) {
        if ((r.resultText() == null || r.resultText().isBlank()) && r.fileId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Provide result text or a file");
        if (r.fileId() != null) files.requireReady(tenantId, r.fileId(), Set.of("LAB_RESULT", "MEDICAL_DOCUMENT"), byPatient ? by : null);
        jdbc.sql("INSERT INTO medical.lab_results (tenant_id, lab_order_id, file_id, result_text, result_summary, uploaded_by, uploaded_by_patient, patient_visible) VALUES (:t,:o,:f,:rt,:rs,:u,:bp,:pv)")
                .param("t", tenantId).param("o", labOrderId).param("f", r.fileId()).param("rt", r.resultText()).param("rs", r.resultSummary()).param("u", by).param("bp", byPatient).param("pv", r.patientVisible()).update();
    }

    @Transactional
    public Map<String, Object> reviewLab(UUID tenantId, UUID actor, UUID labOrderId, boolean shareWithPatient) {
        lockLab(tenantId, labOrderId);
        if (jdbc.sql("SELECT count(*) FROM medical.lab_results WHERE lab_order_id = :o").param("o", labOrderId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("NO_RESULTS", "There are no results to review");
        jdbc.sql("UPDATE medical.lab_results SET reviewed_at = now(), reviewed_by = :u, patient_visible = patient_visible OR :s WHERE lab_order_id = :o").param("u", actor).param("s", shareWithPatient).param("o", labOrderId).update();
        jdbc.sql("UPDATE medical.lab_orders SET status = 'REVIEWED', reviewed_at = now(), reviewed_by = :u, updated_at = now() WHERE id = :o").param("u", actor).param("o", labOrderId).update();
        audit.record(actor, tenantId, "LAB_RESULT_REVIEWED", "lab_order", labOrderId, null);
        return labOrders(tenantId, "o.id = :x", labOrderId, false).get(0);
    }

    private Map<String, Object> lockLab(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT patient_id, status FROM medical.lab_orders WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Lab order not found"));
    }

    private List<Map<String, Object>> labOrders(UUID tenantId, String cond, Object arg, boolean patientView) {
        var rows = jdbc.sql("SELECT o.id, o.encounter_id, o.patient_id, o.test_name, o.instructions, o.priority, o.status, o.ordered_at, o.due_date, o.reviewed_at FROM medical.lab_orders o WHERE o.tenant_id = :t AND " + cond
                + (patientView ? " AND o.status <> 'CANCELLED'" : "") + " ORDER BY o.ordered_at DESC").param("t", tenantId).param("x", arg).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = Rows.camel(r);
            m.put("results", Rows.camel(jdbc.sql("SELECT id, file_id, result_text, result_summary, uploaded_by_patient, uploaded_at, reviewed_at, patient_visible FROM medical.lab_results WHERE lab_order_id = :o"
                    + (patientView ? " AND patient_visible" : "") + " ORDER BY uploaded_at").param("o", r.get("id")).query().listOfRows()));
            out.add(m);
        }
        return out;
    }

    // ---- documents -----------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> addDocument(UUID tenantId, UUID actor, UUID patientId, DocumentReq r) {
        patients.summary(tenantId, patientId);
        if (r.title() == null || r.title().isBlank() || !Set.of("LAB_RESULT", "SCAN", "RADIOLOGY", "PRESCRIPTION", "REFERRAL", "DISCHARGE_SUMMARY", "OTHER").contains(r.documentType()))
            throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid document");
        if (r.fileId() != null) files.requireReady(tenantId, r.fileId(), Set.of("MEDICAL_DOCUMENT", "LAB_RESULT", "PRESCRIPTION"), null);
        UUID id = jdbc.sql("INSERT INTO medical.medical_documents (tenant_id, patient_id, file_id, document_type, title, description, source, patient_visible, created_by) VALUES (:t,:p,:f,:ty,:ti,:d,'CLINIC',:v,:u) RETURNING id")
                .param("t", tenantId).param("p", patientId).param("f", r.fileId()).param("ty", r.documentType()).param("ti", r.title().trim()).param("d", r.description()).param("v", r.patientVisible()).param("u", actor).query(UUID.class).single();
        audit.record(actor, tenantId, "DOCUMENT_ADDED", "patient", patientId, null);
        return Map.of("id", id);
    }

    // ---- timelines ---------------------------------------------------------------------------------------------------------------

    /** Staff view: the whole chart, newest first. Audited as a patient record view. */
    public Map<String, Object> staffTimeline(UUID tenantId, UUID actor, UUID patientId) {
        Map<String, Object> out = timeline(tenantId, patientId, false);
        audit.record(actor, tenantId, "PATIENT_VIEWED", "patient", patientId, "{\"via\":\"timeline\"}");
        return out;
    }

    /** Patient view: only what the patient is allowed to see - never internal notes, vitals, conditions or audit data (spec 35). */
    public Map<String, Object> patientTimeline(UUID tenantId, UUID userId, UUID patientId) {
        patients.requireAccessible(tenantId, userId, patientId);
        Map<String, Object> out = timeline(tenantId, patientId, true);
        audit.record(userId, tenantId, "PATIENT_VIEWED", "patient", patientId, "{\"by\":\"patient\"}");
        if (out.get("labOrders") instanceof List<?> l && l.stream().anyMatch(o -> o instanceof Map<?, ?> m && m.get("results") instanceof List<?> rs && !rs.isEmpty()))
            audit.record(userId, tenantId, "LAB_RESULT_VIEWED", "patient", patientId, "{\"by\":\"patient\"}");
        return out;
    }

    private Map<String, Object> timeline(UUID tenantId, UUID patientId, boolean patientView) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patients.summary(tenantId, patientId));
        out.put("appointments", Rows.camel(jdbc.sql("""
                SELECT a.id, a.start_at, a.end_at, a.status, d.display_name AS doctor_name, s.name AS service_name, b.name AS branch_name FROM medical.appointments a
                JOIN medical.doctors d ON d.id = a.doctor_id JOIN medical.appointment_services s ON s.id = a.service_id JOIN medical.clinic_branches b ON b.id = a.branch_id
                WHERE a.tenant_id = :t AND a.patient_id = :p ORDER BY a.start_at DESC LIMIT 200
                """).param("t", tenantId).param("p", patientId).query().listOfRows()));
        var encounters = jdbc.sql("SELECT e.id, e.visit_at, e.chief_complaint, e.clinical_summary, e.follow_up_date, d.display_name AS doctor_name FROM medical.encounters e JOIN medical.doctors d ON d.id = e.doctor_id WHERE e.tenant_id = :t AND e.patient_id = :p ORDER BY e.visit_at DESC LIMIT 200")
                .param("t", tenantId).param("p", patientId).query().listOfRows();
        List<Map<String, Object>> visits = new ArrayList<>();
        for (var e : encounters) {
            Map<String, Object> m = Rows.camel(e);
            if (patientView) {
                m.remove("chiefComplaint");
                m.remove("clinicalSummary");   // clinician-authored summary stays internal unless shared as a patient-visible note
            } else {
                m.put("vitals", Rows.camel(jdbc.sql("SELECT height_cm, weight_kg, temperature_c, heart_rate_bpm, systolic_bp, diastolic_bp, oxygen_saturation, created_at FROM medical.vitals WHERE encounter_id = :e ORDER BY created_at").param("e", e.get("id")).query().listOfRows()));
                m.put("conditions", Rows.camel(jdbc.sql("SELECT name, status, onset_date FROM medical.conditions WHERE encounter_id = :e").param("e", e.get("id")).query().listOfRows()));
            }
            m.put("notes", Rows.camel(jdbc.sql("SELECT id, note_type, content, created_at" + (patientView ? "" : ", is_patient_visible") + " FROM medical.notes WHERE encounter_id = :e" + (patientView ? " AND is_patient_visible" : "") + " ORDER BY created_at")
                    .param("e", e.get("id")).query().listOfRows()));
            visits.add(m);
        }
        out.put("visits", visits);
        out.put("prescriptions", prescriptions(tenantId, "p.patient_id = :x", patientId, patientView));
        out.put("labOrders", labOrders(tenantId, "o.patient_id = :x", patientId, patientView));
        out.put("documents", Rows.camel(jdbc.sql("SELECT id, document_type, title, description, file_id, source, created_at" + (patientView ? "" : ", patient_visible") + " FROM medical.medical_documents WHERE tenant_id = :t AND patient_id = :p"
                + (patientView ? " AND patient_visible" : "") + " ORDER BY created_at DESC").param("t", tenantId).param("p", patientId).query().listOfRows()));
        return out;
    }

    // ---- dashboard ----------------------------------------------------------------------------------------------------------------

    public Map<String, Object> dashboard(UUID tenantId) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", Rows.camel(jdbc.sql("""
                SELECT a.id, a.start_at, a.status, a.queue_number, p.first_name || ' ' || p.last_name AS patient_name, p.patient_code, d.display_name AS doctor_name, s.name AS service_name
                FROM medical.appointments a JOIN medical.patients p ON p.id = a.patient_id JOIN medical.doctors d ON d.id = a.doctor_id JOIN medical.appointment_services s ON s.id = a.service_id
                WHERE a.tenant_id = :t AND (a.start_at AT TIME ZONE :tz)::date = (now() AT TIME ZONE :tz)::date AND a.status NOT IN ('CANCELLED','REJECTED') ORDER BY a.start_at
                """).param("t", tenantId).param("tz", tz).query().listOfRows()));
        out.put("pendingRequests", jdbc.sql("SELECT count(*) FROM medical.appointments WHERE tenant_id = :t AND status = 'PENDING_CONFIRMATION'").param("t", tenantId).query(Long.class).single());
        out.put("checkedIn", jdbc.sql("SELECT count(*) FROM medical.appointments WHERE tenant_id = :t AND status = 'CHECKED_IN'").param("t", tenantId).query(Long.class).single());
        out.put("pendingLabReviews", jdbc.sql("SELECT count(*) FROM medical.lab_orders WHERE tenant_id = :t AND status IN ('PATIENT_UPLOADED','UNDER_REVIEW')").param("t", tenantId).query(Long.class).single());
        var month = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows, count(*) FILTER (WHERE status = 'COMPLETED') AS completed, count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       coalesce(sum(price_minor) FILTER (WHERE payment_status = 'PAID'), 0) AS revenue FROM medical.appointments
                WHERE tenant_id = :t AND date_trunc('month', start_at AT TIME ZONE :tz) = date_trunc('month', now() AT TIME ZONE :tz)
                """).param("t", tenantId).param("tz", tz).query().singleRow();
        var unpaid = jdbc.sql("SELECT count(*) AS c, coalesce(sum(price_minor), 0) AS t FROM medical.appointments WHERE tenant_id = :t AND payment_status = 'UNPAID' AND coalesce(price_minor, 0) > 0 AND status IN ('CHECKED_IN','IN_PROGRESS','COMPLETED')")
                .param("t", tenantId).query().singleRow();
        out.put("unpaidVisitsCount", unpaid.get("c"));
        out.put("unpaidVisitsMinor", unpaid.get("t"));
        out.put("noShowsThisMonth", month.get("no_shows"));
        out.put("completedThisMonth", month.get("completed"));
        out.put("cancelledThisMonth", month.get("cancelled"));
        out.put("revenueThisMonthMinor", month.get("revenue"));
        out.put("newPatientsThisMonth", jdbc.sql("SELECT count(*) FROM medical.patients WHERE tenant_id = :t AND date_trunc('month', created_at AT TIME ZONE :tz) = date_trunc('month', now() AT TIME ZONE :tz)").param("t", tenantId).param("tz", tz).query(Long.class).single());
        return out;
    }

}

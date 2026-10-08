package com.platform.medical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.audit.AuditService;
import com.platform.files.FileService;
import com.platform.shared.BusinessException;
import com.platform.shared.RateLimitStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * "Download my record" (spec 17/37): a ZIP with the record as JSON, every prescription as a PDF and the uploaded lab/result files.
 * A patient (or guardian) gets exactly what the portal shows them; staff with patient.export get the full chart. Always audited, rate limited,
 * and size capped so one request cannot exhaust the server.
 */
@Service
public class PatientExportService {
    private static final long MAX_BYTES = 150L * 1024 * 1024;

    private final ClinicalService clinical;
    private final PatientService patients;
    private final PrescriptionPdfService pdf;
    private final FileService files;
    private final AuditService audit;
    private final RateLimitStore limits;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public PatientExportService(ClinicalService clinical, PatientService patients, PrescriptionPdfService pdf, FileService files, AuditService audit, RateLimitStore limits) {
        this.clinical = clinical;
        this.patients = patients;
        this.pdf = pdf;
        this.files = files;
        this.audit = audit;
        this.limits = limits;
    }

    @SuppressWarnings("unchecked")
    public byte[] export(UUID tenantId, UUID actor, UUID patientId, boolean patientView) {
        if (!limits.tryAcquire("export:" + actor, 3, Duration.ofHours(1)))
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Too many exports, try again later");
        Map<String, Object> record = patientView ? clinical.patientTimeline(tenantId, actor, patientId) : clinical.staffTimeline(tenantId, actor, patientId);
        List<String> notes = new ArrayList<>();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long[] total = {0};
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "record.json", json.writeValueAsBytes(record), total);

            for (Map<String, Object> rx : (List<Map<String, Object>>) record.get("prescriptions")) {
                if ("DRAFT".equals(rx.get("status"))) continue;
                UUID id = (UUID) rx.get("id");
                try {
                    String day = String.valueOf(rx.get("issuedAt") == null ? "" : rx.get("issuedAt")).replaceAll("[^0-9]", "").substring(0, Math.min(8, String.valueOf(rx.get("issuedAt") == null ? "" : rx.get("issuedAt")).replaceAll("[^0-9]", "").length()));
                    put(zip, "prescriptions/" + (day.isEmpty() ? "" : day + "-") + id.toString().substring(0, 8) + ".pdf", pdf.render(tenantId, id, patientView), total);
                } catch (RuntimeException e) {
                    notes.add("Prescription " + id + " could not be included.");
                }
            }

            Set<UUID> fileIds = new HashSet<>();
            for (Map<String, Object> lab : (List<Map<String, Object>>) record.get("labOrders"))
                for (Map<String, Object> r : (List<Map<String, Object>>) lab.get("results")) if (r.get("fileId") != null) fileIds.add((UUID) r.get("fileId"));
            for (Map<String, Object> rx : (List<Map<String, Object>>) record.get("prescriptions")) if (rx.get("imageFileId") != null && !"DRAFT".equals(rx.get("status"))) fileIds.add((UUID) rx.get("imageFileId"));
            for (Map<String, Object> d : (List<Map<String, Object>>) record.get("documents")) if (d.get("fileId") != null) fileIds.add((UUID) d.get("fileId"));
            int n = 1;
            for (UUID fid : fileIds) {
                var raw = files.readForExport(tenantId, fid);
                if (raw.isEmpty()) { notes.add("File " + fid + " is no longer available."); continue; }
                if (total[0] + raw.get().data().length > MAX_BYTES) { notes.add("File " + fid + " was left out because the export would be too large; request it separately."); continue; }
                put(zip, "files/" + (n++) + "-" + raw.get().filename().replaceAll("[^A-Za-z0-9._-]", "_"), raw.get().data(), total);
            }
            if (!notes.isEmpty()) put(zip, "NOTES.txt", String.join("\n", notes).getBytes(StandardCharsets.UTF_8), total);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not build the export", e);
        }
        audit.record(actor, tenantId, "PATIENT_EXPORTED", "patient", patientId, "{\"by\":\"" + (patientView ? "patient" : "staff") + "\",\"bytes\":" + bos.size() + "}");
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data, long[] total) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
        total[0] += data.length;
    }
}

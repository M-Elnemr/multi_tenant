package com.platform.files;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped private-by-default files (spec 9 / 45): authorize -> short-lived signed upload URL ->
 * upload -> verified (size, type, magic bytes, checksum) -> READY. Reads are re-authorized every time.
 */
@Service
public class FileService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> DOC_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final Set<String> MEDICAL = Set.of("LAB_RESULT", "MEDICAL_DOCUMENT", "PRESCRIPTION");
    private static final long IMAGE_MAX = 5L * 1024 * 1024;
    private static final long DOC_MAX = 15L * 1024 * 1024;
    private static final long URL_TTL_SECONDS = 300;

    public record Download(byte[] data, String contentType, String filename, boolean isPublic) {}

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final AuditService audit;
    private final byte[] signingKey;

    public FileService(JdbcClient jdbc, FileStorage storage, AuditService audit, @Value("${app.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.signingKey = ("file-upload:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public Map<String, Object> presign(UUID tenantId, UUID userId, String filename, String contentType, long size, String category, String visibility) {
        boolean imageOnly = Set.of("PRODUCT_IMAGE", "LOGO", "AVATAR").contains(category);
        if (!Set.of("PRODUCT_IMAGE", "LOGO", "AVATAR", "LAB_RESULT", "MEDICAL_DOCUMENT", "PRESCRIPTION").contains(category)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Unknown category");
        if (contentType == null || !(imageOnly ? IMAGE_TYPES : DOC_TYPES).contains(contentType.toLowerCase())) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "This file type is not allowed");
        long max = imageOnly ? IMAGE_MAX : DOC_MAX;
        if (size <= 0 || size > max) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File too large (max " + (max / 1024 / 1024) + " MB)");
        String vis = visibility == null ? defaultVisibility(category) : visibility;
        if (!Set.of("PRIVATE", "TENANT_INTERNAL", "CUSTOMER_VISIBLE", "PATIENT_VISIBLE", "PUBLIC").contains(vis)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid visibility");
        if (MEDICAL.contains(category) && !Set.of("PRIVATE", "TENANT_INTERNAL", "PATIENT_VISIBLE").contains(vis)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Medical files can never be public");
        if (!MEDICAL.contains(category) && Set.of("PATIENT_VISIBLE").contains(vis)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid visibility for this category");
        UUID id = UUID.randomUUID();
        String key = "tenants/" + tenantId + "/" + category.toLowerCase() + "/" + id + extension(contentType);
        jdbc.sql("INSERT INTO core.files (id, tenant_id, owner_user_id, object_key, original_filename, content_type, declared_size, category, visibility) VALUES (:i,:t,:u,:k,:f,:c,:s,:ca,:v)")
                .param("i", id).param("t", tenantId).param("u", userId).param("k", key).param("f", safeName(filename)).param("c", contentType.toLowerCase()).param("s", size).param("ca", category).param("v", vis).update();
        long exp = Instant.now().getEpochSecond() + URL_TTL_SECONDS;
        return Map.of("fileId", id, "uploadUrl", "/api/v1/files/" + id + "/content?exp=" + exp + "&sig=" + sign(id, exp), "method", "PUT", "expiresIn", URL_TTL_SECONDS, "maxBytes", size);
    }

    /** The signed URL is the credential here (like an S3 presigned URL); everything else about the file was fixed at presign time. */
    @Transactional
    public Map<String, Object> upload(UUID tenantId, UUID fileId, long exp, String sig, String contentType, byte[] data) {
        if (Instant.now().getEpochSecond() > exp || !MessageDigest.isEqual(sign(fileId, exp).getBytes(StandardCharsets.UTF_8), String.valueOf(sig).getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(HttpStatus.FORBIDDEN, "UPLOAD_EXPIRED", "Upload link is invalid or expired");
        var f = jdbc.sql("SELECT object_key, content_type, declared_size, status FROM core.files WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found"));
        if (!"PENDING".equals(f.get("status"))) throw BusinessException.conflict("UPLOAD_EXPIRED", "This upload was already completed");
        String declaredType = (String) f.get("content_type");
        long declared = ((Number) f.get("declared_size")).longValue();
        if (contentType == null || !contentType.toLowerCase().startsWith(declaredType)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Content type differs from the declared one");
        if (data.length == 0 || data.length > declared) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File size differs from the declared size");
        if (!magicMatches(declaredType, data)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File content does not match its type");
        // virus/malware scan hook would run here before the file becomes READY
        try {
            storage.put((String) f.get("object_key"), data);
        } catch (IOException e) {
            throw new IllegalStateException("Storage failure", e);
        }
        jdbc.sql("UPDATE core.files SET status = 'READY', file_size = :s, checksum = :c, uploaded_at = now() WHERE id = :i").param("s", (long) data.length).param("c", sha256(data)).param("i", fileId).update();
        return Map.of("fileId", fileId, "status", "READY", "size", data.length);
    }

    /** A file another module may reference: same tenant, upload finished, right category. Returns its owner. */
    public UUID requireReady(UUID tenantId, UUID fileId, Set<String> categories, UUID mustBeOwnedBy) {
        var f = jdbc.sql("SELECT owner_user_id, category, status FROM core.files WHERE id = :i AND tenant_id = :t").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.badRequest("FILE_NOT_ALLOWED", "File not found"));
        if (!"READY".equals(f.get("status")) || !categories.contains((String) f.get("category"))) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File cannot be used here");
        if (mustBeOwnedBy != null && !mustBeOwnedBy.equals(f.get("owner_user_id"))) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File cannot be used here");
        return (UUID) f.get("owner_user_id");
    }

    /**
     * Authorizes and returns bytes. userId/permissions are null/empty for anonymous callers. Private files are readable by
     * their uploader, by clinical staff (patient.read + a clinical permission - upload alone is not enough) / store staff with product.update, and - for medical files -
     * by the patient (or guardian) the file was shared with. Anything else is a 404 so existence is not revealed.
     */
    public Download download(UUID tenantId, UUID fileId, UUID userId, Set<String> authorities) {
        var f = jdbc.sql("SELECT object_key, content_type, original_filename, visibility, category, owner_user_id, status FROM core.files WHERE id = :i AND tenant_id = :t").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .filter(r -> "READY".equals(r.get("status"))).orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found"));
        String vis = (String) f.get("visibility");
        String category = (String) f.get("category");
        boolean isPublic = "PUBLIC".equals(vis);
        boolean allowed = isPublic;
        if (!allowed && userId != null) {
            allowed = userId.equals(f.get("owner_user_id"))
                    || (MEDICAL.contains(category) ? authorities.contains("patient.read") && (authorities.contains("medical_note.create") || authorities.contains("lab_result.review"))
                                                    : authorities.contains("product.update") || authorities.contains("settings.manage"))
                    || (MEDICAL.contains(category) && sharedWithPatient(tenantId, fileId, userId));
        }
        if (!allowed) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found");
        if (MEDICAL.contains(category)) audit.record(userId, tenantId, "LAB_RESULT_VIEWED", "file", fileId, "{\"category\":\"" + category + "\"}");
        try {
            return new Download(storage.get((String) f.get("object_key")), (String) f.get("content_type"), (String) f.get("original_filename"), isPublic);
        } catch (IOException e) {
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found");
        }
    }

    private boolean sharedWithPatient(UUID tenantId, UUID fileId, UUID userId) {
        return jdbc.sql("""
                SELECT count(*) FROM (
                  SELECT o.patient_id FROM medical.lab_results r JOIN medical.lab_orders o ON o.id = r.lab_order_id WHERE r.tenant_id = :t AND r.file_id = :f AND r.patient_visible
                  UNION ALL SELECT d.patient_id FROM medical.medical_documents d WHERE d.tenant_id = :t AND d.file_id = :f AND d.patient_visible
                ) x WHERE x.patient_id IN (SELECT id FROM medical.patients WHERE tenant_id = :t AND status = 'ACTIVE' AND (user_id = :u
                      OR id IN (SELECT patient_id FROM medical.patient_guardians WHERE tenant_id = :t AND guardian_user_id = :u)))
                """).param("t", tenantId).param("f", fileId).param("u", userId).query(Long.class).single() > 0;
    }

    /** file_cleanup job: abandoned uploads (never completed) are removed after a day. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @Transactional
    public void cleanupAbandoned() {
        List<String> keys = jdbc.sql("DELETE FROM core.files WHERE status = 'PENDING' AND created_at < now() - interval '1 day' RETURNING object_key").query(String.class).list();
        for (String k : keys) {
            try { storage.delete(k); } catch (IOException ignored) { /* never written */ }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String defaultVisibility(String category) { return Set.of("PRODUCT_IMAGE", "LOGO").contains(category) ? "PUBLIC" : "PRIVATE"; }

    private static String extension(String contentType) {
        return switch (contentType.toLowerCase()) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "application/pdf" -> ".pdf"; default -> ""; };
    }

    private static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[\\\\/\\r\\n\\x00]", "_").trim();
        return n.isEmpty() ? "file" : (n.length() > 200 ? n.substring(0, 200) : n);
    }

    static boolean magicMatches(String type, byte[] d) {
        if (d.length < 12) return false;
        return switch (type) {
            case "image/png" -> (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
            case "image/jpeg" -> (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
            case "image/webp" -> d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
            case "application/pdf" -> d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F';
            default -> false;
        };
    }

    private String sign(UUID id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] d) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d)); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

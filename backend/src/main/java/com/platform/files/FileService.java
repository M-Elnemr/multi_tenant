package com.platform.files;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.files.FileStorage.Bucket;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * Tenant-scoped files (spec 9 / 45):
 *  authorize -> short-lived signed upload URL -> upload -> validated (size, type, magic bytes) -> READY.
 * Pictures that will be shown publicly (product images, logos, avatars) are decoded, rotated upright, stripped of EXIF/GPS, re-encoded and
 * stored as bounded variants in the PUBLIC bucket under immutable keys. Medical files go to the PRIVATE bucket unchanged and are only ever
 * streamed after an authorization check. Every object key lives under tenants/{tenantId}/ and is looked up through core.files, never taken from a client.
 * Storage use is metered per tenant and limited by the plan (max_storage_mb).
 */
@Service
public class FileService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");
    private static final Set<String> DOC_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> MEDICAL = Set.of("LAB_RESULT", "MEDICAL_DOCUMENT", "PRESCRIPTION");
    private static final Set<String> PROCESSED = Set.of("PRODUCT_IMAGE", "LOGO", "AVATAR");
    private static final long IMAGE_MAX = 5L * 1024 * 1024;
    private static final long DOC_MAX = 15L * 1024 * 1024;
    private static final long URL_TTL_SECONDS = 300;
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    public record Download(byte[] data, String contentType, String filename, boolean isPublic, String etag, String redirectPath) {}

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final AuditService audit;
    private final ImageProcessor images;
    private final EntitlementService ent;
    private final byte[] signingKey;

    public FileService(JdbcClient jdbc, FileStorage storage, AuditService audit, ImageProcessor images, EntitlementService ent, @Value("${app.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.images = images;
        this.ent = ent;
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
        requireStorageRoom(tenantId, size);
        UUID id = UUID.randomUUID();
        String key = "tenants/" + tenantId + "/" + category.toLowerCase() + "/" + id + extension(contentType);
        jdbc.sql("INSERT INTO core.files (id, tenant_id, owner_user_id, object_key, original_filename, content_type, declared_size, category, visibility, bucket) VALUES (:i,:t,:u,:k,:f,:c,:s,:ca,:v,:b)")
                .param("i", id).param("t", tenantId).param("u", userId).param("k", key).param("f", safeName(filename)).param("c", contentType.toLowerCase()).param("s", size).param("ca", category).param("v", vis)
                .param("b", bucketFor(category, vis).name()).update();
        long exp = Instant.now().getEpochSecond() + URL_TTL_SECONDS;
        return Map.of("fileId", id, "uploadUrl", "/api/v1/files/" + id + "/content?exp=" + exp + "&sig=" + sign(id, exp), "method", "PUT", "expiresIn", URL_TTL_SECONDS, "maxBytes", size);
    }

    /** The signed URL is the credential here (like an S3 presigned URL); everything else about the file was fixed at presign time. */
    @Transactional
    public Map<String, Object> upload(UUID tenantId, UUID fileId, long exp, String sig, String contentType, byte[] data) {
        if (Instant.now().getEpochSecond() > exp || !MessageDigest.isEqual(sign(fileId, exp).getBytes(StandardCharsets.UTF_8), String.valueOf(sig).getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(HttpStatus.FORBIDDEN, "UPLOAD_EXPIRED", "Upload link is invalid or expired");
        var f = jdbc.sql("SELECT object_key, content_type, declared_size, status, category, bucket FROM core.files WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found"));
        if (!"PENDING".equals(f.get("status"))) throw BusinessException.conflict("UPLOAD_EXPIRED", "This upload was already completed");
        String declaredType = (String) f.get("content_type");
        long declared = ((Number) f.get("declared_size")).longValue();
        if (contentType == null || !contentType.toLowerCase().startsWith(declaredType)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Content type differs from the declared one");
        if (data.length == 0 || data.length > declared) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File size differs from the declared size");
        if (!magicMatches(declaredType, data)) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File content does not match its type");
        requireStorageRoom(tenantId, data.length);
        // a virus/malware scan hook would run here before the file becomes READY
        String category = (String) f.get("category");
        Bucket bucket = Bucket.valueOf((String) f.get("bucket"));
        String checksum = sha256(data);
        long stored;
        String variantsJson = null;
        String publicBase = null, publicExt = null, mainKey = (String) f.get("object_key");
        try {
            if (PROCESSED.contains(category)) {
                List<ImageProcessor.Variant> vs = images.process(data, declaredType);
                String prefix = "tenants/" + tenantId + "/img/" + fileId + "/" + checksum.substring(0, 12);
                Map<String, Object> meta = new LinkedHashMap<>();
                stored = 0;
                for (ImageProcessor.Variant v : vs) {
                    String key = prefix + "-" + v.name() + "." + v.ext();
                    storage.put(bucket, key, v.data(), v.contentType(), IMMUTABLE);
                    meta.put(v.name(), Map.of("key", key, "w", v.width(), "h", v.height(), "size", v.data().length, "type", v.contentType()));
                    stored += v.data().length;
                }
                mainKey = prefix + "-original." + vs.get(0).ext();
                variantsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
                if (bucket == Bucket.PUBLIC) { publicBase = prefix; publicExt = vs.get(0).ext(); }
                declaredType = vs.get(0).contentType();
            } else {
                storage.put(bucket, mainKey, data, declaredType, "private, no-store");
                stored = data.length;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Storage failure", e);
        }
        jdbc.sql("UPDATE core.files SET status = 'READY', object_key = :k, content_type = :c, file_size = :s, stored_bytes = :sb, checksum = :ck, variants = CAST(:v AS jsonb), public_base = :pb, public_ext = :pe, uploaded_at = now() WHERE id = :i")
                .param("k", mainKey).param("c", declaredType).param("s", (long) data.length).param("sb", stored).param("ck", checksum).param("v", variantsJson).param("pb", publicBase).param("pe", publicExt).param("i", fileId).update();
        ent.increment(tenantId, "storage_bytes", "ALL", stored);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileId", fileId);
        out.put("status", "READY");
        out.put("size", stored);
        if (publicBase != null) { out.put("mediaBase", storage.publicPath(publicBase) == null ? null : storage.publicPath(publicBase)); out.put("mediaExt", publicExt); }
        return out;
    }

    /** A file another module may reference: same tenant, upload finished, right category. Returns its owner. */
    public UUID requireReady(UUID tenantId, UUID fileId, Set<String> categories, UUID mustBeOwnedBy) {
        var f = jdbc.sql("SELECT owner_user_id, category, status FROM core.files WHERE id = :i AND tenant_id = :t").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.badRequest("FILE_NOT_ALLOWED", "File not found"));
        if (!"READY".equals(f.get("status")) || !categories.contains((String) f.get("category"))) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File cannot be used here");
        if (mustBeOwnedBy != null && !mustBeOwnedBy.equals(f.get("owner_user_id"))) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "File cannot be used here");
        return (UUID) f.get("owner_user_id");
    }

    /** Public-path pieces a module stores next to its own row so pages can link the immutable image directly (no app hop per image). */
    public Map<String, String> publicLocation(UUID tenantId, UUID fileId) {
        return jdbc.sql("SELECT public_base, public_ext FROM core.files WHERE id = :i AND tenant_id = :t AND bucket = 'PUBLIC' AND public_base IS NOT NULL").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .map(r -> {
                    String path = storage.publicPath((String) r.get("public_base"));
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("base", path);
                    m.put("ext", (String) r.get("public_ext"));
                    return path == null ? Map.<String, String>of() : m;
                }).orElse(Map.of());
    }

    /**
     * Authorizes and returns bytes (or, for public images, a redirect to the immutable object). userId/authorities are null/empty for anonymous callers.
     * Private files are readable by their uploader, by clinical staff (patient.read + a clinical permission), by store staff with product.update, and - for
     * medical files - by the patient (or guardian) the file was shared with. Anything else is a 404 so existence is not revealed.
     */
    public Download download(UUID tenantId, UUID fileId, String variant, UUID userId, Set<String> authorities) {
        var f = jdbc.sql("SELECT object_key, content_type, original_filename, visibility, category, owner_user_id, status, bucket, variants::text AS variants, checksum FROM core.files WHERE id = :i AND tenant_id = :t").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
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

        String key = (String) f.get("object_key");
        String type = (String) f.get("content_type");
        String etag = "\"" + f.get("checksum") + (variant == null ? "" : "-" + variant) + "\"";
        if (f.get("variants") != null) {
            Map<String, Object> vs = Rows.json(f.get("variants"));
            Object chosen = vs.get(variant == null ? "original" : variant);
            if (chosen instanceof Map<?, ?> m) {
                key = (String) m.get("key");
                type = (String) m.get("type");
            }
        }
        Bucket bucket = Bucket.valueOf((String) f.get("bucket"));
        if (isPublic && bucket == Bucket.PUBLIC && storage.publicPath(key) != null)
            return new Download(null, type, (String) f.get("original_filename"), true, etag, storage.publicPath(key));
        try {
            return new Download(storage.get(bucket, key), type, (String) f.get("original_filename"), isPublic, etag, null);
        } catch (IOException e) {
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found");
        }
    }

    public record Raw(String filename, byte[] data) {}

    /** For record exports: the caller has already decided this file may be included; only tenant + READY are checked here. Empty if the object is gone. */
    public java.util.Optional<Raw> readForExport(UUID tenantId, UUID fileId) {
        var f = jdbc.sql("SELECT object_key, original_filename, bucket, status FROM core.files WHERE id = :i AND tenant_id = :t").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst();
        if (f.isEmpty() || !"READY".equals(f.get().get("status"))) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(new Raw((String) f.get().get("original_filename"), storage.get(Bucket.valueOf((String) f.get().get("bucket")), (String) f.get().get("object_key"))));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** Deletes a file and frees its quota. Refused while anything still points at it (product image, logo, lab result, document). */
    @Transactional
    public void delete(UUID tenantId, UUID userId, Set<String> authorities, UUID fileId) {
        var f = jdbc.sql("SELECT owner_user_id, category, bucket, object_key, variants::text AS variants, stored_bytes, status FROM core.files WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", fileId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found"));
        String category = (String) f.get("category");
        boolean mayManage = userId.equals(f.get("owner_user_id")) || (MEDICAL.contains(category) ? authorities.contains("lab_result.review") : authorities.contains("product.update") || authorities.contains("settings.manage"));
        if (!mayManage) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "File not found");
        Long uses = jdbc.sql("""
                SELECT (SELECT count(*) FROM commerce.product_media WHERE file_id = :f) + (SELECT count(*) FROM core.branding WHERE logo_file_id = :f OR favicon_file_id = :f)
                     + (SELECT count(*) FROM medical.lab_results WHERE file_id = :f) + (SELECT count(*) FROM medical.medical_documents WHERE file_id = :f)
                """).param("f", fileId).query(Long.class).single();
        if (uses > 0) throw BusinessException.conflict("FILE_IN_USE", "This file is still in use");
        removeObjects(f);
        jdbc.sql("DELETE FROM core.files WHERE id = :i").param("i", fileId).update();
        long stored = ((Number) f.get("stored_bytes")).longValue();
        if (stored > 0) ent.increment(tenantId, "storage_bytes", "ALL", -stored);
        audit.record(userId, tenantId, "FILE_DELETED", "file", fileId, null);
    }

    private void removeObjects(Map<String, Object> f) {
        Bucket bucket = Bucket.valueOf((String) f.get("bucket"));
        try {
            if (f.get("variants") != null) {
                for (Object v : Rows.json(f.get("variants")).values())
                    if (v instanceof Map<?, ?> m) storage.delete(bucket, (String) m.get("key"));
            } else {
                storage.delete(bucket, (String) f.get("object_key"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Storage delete failed", e);
        }
    }

    /** Current usage and the plan allowance, for dashboards. */
    public Map<String, Object> usage(UUID tenantId) {
        long used = ent.usage(tenantId, "storage_bytes", "ALL");
        Long limitMb = ent.limit(tenantId, "max_storage_mb").orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usedBytes", used);
        m.put("limitBytes", limitMb == null ? null : limitMb * 1024 * 1024);
        return m;
    }

    private void requireStorageRoom(UUID tenantId, long incoming) {
        ent.limit(tenantId, "max_storage_mb").ifPresent(mb -> {
            if (ent.usage(tenantId, "storage_bytes", "ALL") + incoming > mb * 1024 * 1024)
                throw new BusinessException(HttpStatus.FORBIDDEN, "PLAN_LIMIT_REACHED", "Storage limit reached for your plan");
        });
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
        jdbc.sql("DELETE FROM core.files WHERE status = 'PENDING' AND created_at < now() - interval '1 day'").update();   // nothing was ever written for a PENDING file
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String defaultVisibility(String category) { return Set.of("PRODUCT_IMAGE", "LOGO").contains(category) ? "PUBLIC" : "PRIVATE"; }

    private static Bucket bucketFor(String category, String visibility) { return "PUBLIC".equals(visibility) && PROCESSED.contains(category) ? Bucket.PUBLIC : Bucket.PRIVATE; }

    private static String extension(String contentType) {
        return switch (contentType.toLowerCase()) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; case "application/pdf" -> ".pdf"; default -> ""; };
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

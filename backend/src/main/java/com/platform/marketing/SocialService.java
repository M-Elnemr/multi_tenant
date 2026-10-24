package com.platform.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.chat.MetaGraph;
import com.platform.chat.TokenCrypto;
import com.platform.files.FileService;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-written posts for the shop's own Facebook page and Instagram account: pick a product (or the whole shop), get a caption and a picture, edit, then post now or schedule.
 * Posts go out through the pages the owner connected with Facebook login (the same connection the AI assistant uses).
 */
@Service
public class SocialService {
    private static final Logger log = LoggerFactory.getLogger(SocialService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final AiContentService content;
    private final ImageGenerator images;
    private final FileService files;
    private final OpenUrls urls;
    private final MetaGraph graph;
    private final TokenCrypto crypto;
    private final AuditService audit;
    private final boolean scheduler;

    public SocialService(JdbcClient jdbc, AiContentService content, ImageGenerator images, FileService files, OpenUrls urls, MetaGraph graph, TokenCrypto crypto, AuditService audit,
                         @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.content = content;
        this.images = images;
        this.files = files;
        this.urls = urls;
        this.graph = graph;
        this.crypto = crypto;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    public boolean imagesAvailable() { return images.available(); }

    /** The pages and accounts posts can go to (never the tokens). */
    public List<Map<String, Object>> channels(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, platform, external_id, name, status FROM chat.channels WHERE tenant_id = :t AND platform IN ('FACEBOOK', 'INSTAGRAM') ORDER BY platform, created_at").param("t", tenantId).query().listOfRows());
    }

    // ---- composing ---------------------------------------------------------------------------------------------

    public record DraftReq(String kind, UUID productId, String language, String tone, Boolean withImage, List<String> channels) {}

    public Map<String, Object> draft(UUID tenantId, UUID actor, DraftReq r) {
        String kind = "SHOP".equalsIgnoreCase(r.kind()) ? "SHOP" : "PRODUCT";
        if ("PRODUCT".equals(kind) && r.productId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose a product");
        AiContentService.Subject subject = subject(tenantId, kind, r.productId());
        String language = r.language() == null || r.language().isBlank() ? tenantLocale(tenantId) : r.language();
        AiContentService.Draft d = content.post(subject, language, r.tone(), country(tenantId));
        String caption = d.caption();
        List<String> chans = cleanChannels(r.channels(), tenantId);
        UUID id = jdbc.sql("""
                INSERT INTO marketing.posts (tenant_id, product_id, kind, channels, caption, created_by, results) VALUES (:t, :p, :k, CAST(:c AS text[]), :cap, :u, CAST(:r AS jsonb)) RETURNING id
                """).param("t", tenantId).param("p", "PRODUCT".equals(kind) ? r.productId() : null).param("k", kind).param("c", "{" + String.join(",", chans) + "}").param("cap", withTags(caption, d.hashtags()))
                .param("u", actor).param("r", meta(d)).query(UUID.class).single();
        if (!Boolean.FALSE.equals(r.withImage()) && images.available()) {
            try { attachAiImage(tenantId, actor, id, d.imagePrompt(), r.productId()); } catch (RuntimeException e) { log.info("Post image skipped: {}", e.getMessage()); }
        }
        audit.record(actor, tenantId, "SOCIAL_POST_DRAFTED", "marketing_post", id, null);
        return get(tenantId, id);
    }

    private static String meta(AiContentService.Draft d) {
        try { return JSON.writeValueAsString(List.of(Map.of("meta", true, "imagePrompt", d.imagePrompt() == null ? "" : d.imagePrompt(), "fromAi", d.fromAi()))); } catch (Exception e) { return "[]"; }
    }

    private static String withTags(String caption, List<String> tags) {
        if (tags == null || tags.isEmpty()) return caption;
        return caption + "\n\n" + String.join(" ", tags.stream().map((t) -> "#" + t).toList());
    }

    private AiContentService.Subject subject(UUID tenantId, String kind, UUID productId) {
        var shop = jdbc.sql("SELECT coalesce(sp.store_name, t.name) AS name, sp.about, sp.short_description FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query().singleRow();
        String shopName = (String) shop.get("name");
        String about = (String) (shop.get("about") != null ? shop.get("about") : shop.get("short_description"));
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        if ("SHOP".equals(kind)) {
            List<String> top = jdbc.sql("SELECT name FROM commerce.products WHERE tenant_id = :t AND status = 'ACTIVE' ORDER BY sold_count DESC, created_at DESC LIMIT 5").param("t", tenantId).query(String.class).list();
            return new AiContentService.Subject("SHOP", shopName, about, null, null, null, null, 0, null, null, urls.baseUrl(host), top);
        }
        var p = jdbc.sql("""
                SELECT p.name, p.description, p.brand, p.slug, p.currency, tx.name_ar AS category,
                  (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS price,
                  (SELECT max(v.compare_at_price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.compare_at_price_minor > v.price_minor) AS compare
                FROM commerce.products p LEFT JOIN commerce.taxonomy tx ON tx.id = p.taxonomy_id WHERE p.id = :p AND p.tenant_id = :t AND p.status <> 'ARCHIVED'
                """).param("p", productId).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        return new AiContentService.Subject("PRODUCT", shopName, about, (String) p.get("name"), (String) p.get("description"), (String) p.get("brand"), (String) p.get("category"), p.get("price") == null ? 0 : ((Number) p.get("price")).longValue(),
                p.get("compare") == null ? null : ((Number) p.get("compare")).longValue(), (String) p.get("currency"), urls.baseUrl(host) + "/products/" + java.net.URLEncoder.encode((String) p.get("slug"), StandardCharsets.UTF_8), List.of());
    }

    private String tenantLocale(UUID tenantId) { return jdbc.sql("SELECT default_locale FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("ar"); }

    static String countryOf(String currency) { return "SAR".equals(currency) ? "SA" : "AED".equals(currency) ? "AE" : "EG"; }

    String country(UUID tenantId) { return countryOf(jdbc.sql("SELECT default_currency FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("EGP")); }

    private List<String> cleanChannels(List<String> in, UUID tenantId) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String c : in) { String u = c == null ? "" : c.toUpperCase(); if ((u.equals("FACEBOOK") || u.equals("INSTAGRAM")) && !out.contains(u)) out.add(u); }
        if (in == null || in.isEmpty()) for (var c : channels(tenantId)) { String p = (String) c.get("platform"); if (!out.contains(p)) out.add(p); }   // default: everywhere connected
        return out;
    }

    // ---- pictures ------------------------------------------------------------------------------------------------

    /** Makes the picture with AI (from the product's own photo when it has one) and stores it as a public file the platforms can download. */
    public Map<String, Object> attachAiImage(UUID tenantId, UUID actor, UUID postId, String prompt, UUID productId) {
        if (!images.available()) throw BusinessException.badRequest("AI_NOT_AVAILABLE", "AI images are not set up yet");
        byte[] ref = null;
        String refType = null;
        if (productId != null) {
            var media = jdbc.sql("SELECT file_id FROM commerce.product_media WHERE product_id = :p AND file_id IS NOT NULL ORDER BY sort_order LIMIT 1").param("p", productId).query(UUID.class).optional();
            if (media.isPresent()) {
                var raw = files.readForExport(tenantId, media.get());
                if (raw.isPresent()) { ref = raw.get().data(); refType = ref.length > 3 && ref[0] == (byte) 0x89 ? "image/png" : "image/jpeg"; }
            }
        }
        ImageGenerator.Generated g;
        try { g = images.generate(prompt, ref, refType); } catch (ImageGenerator.ImageException e) { throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "AI_IMAGE_FAILED", e.getMessage()); }
        String type = g.contentType() != null && g.contentType().toLowerCase().contains("png") ? "image/png" : "image/jpeg";
        UUID fileId = storePublic(tenantId, actor, g.data(), type);
        jdbc.sql("UPDATE marketing.posts SET image_file_id = :f, image_url = :u WHERE id = :i AND tenant_id = :t").param("f", fileId).param("u", publicUrl(tenantId, fileId)).param("i", postId).param("t", tenantId).update();
        return get(tenantId, postId);
    }

    @Transactional
    UUID storePublic(UUID tenantId, UUID actor, byte[] data, String type) {
        Map<String, Object> p = files.presign(tenantId, actor, "post" + (type.endsWith("png") ? ".png" : ".jpg"), type, data.length, "PRODUCT_IMAGE", "PUBLIC");
        UUID fileId = (UUID) p.get("fileId");
        String url = (String) p.get("uploadUrl");
        long exp = Long.parseLong(param(url, "exp"));
        files.upload(tenantId, fileId, exp, URLDecoder.decode(param(url, "sig"), StandardCharsets.UTF_8), type, data);
        return fileId;
    }

    private static String param(String url, String name) {
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        throw new IllegalStateException("missing " + name);
    }

    String publicUrl(UUID tenantId, UUID fileId) {
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        var loc = files.publicLocation(tenantId, fileId);
        if (loc.isEmpty()) return urls.baseUrl(host) + "/api/v1/files/" + fileId + "/content?variant=original";
        return urls.image(host, loc.get("base"), loc.get("ext"), null, "original");
    }

    // ---- reading / editing ------------------------------------------------------------------------------------------

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return view(jdbc.sql(POST_SELECT + " WHERE p.id = :i AND p.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found")));
    }

    private static final String POST_SELECT = "SELECT p.id, p.product_id, pr.name AS product_name, p.kind, array_to_string(p.channels, ',') AS channels, p.caption, p.image_file_id, p.image_url, p.status, p.scheduled_at, p.posted_at, p.results::text AS results, p.created_at "
            + "FROM marketing.posts p LEFT JOIN commerce.products pr ON pr.id = p.product_id";

    @SuppressWarnings("unchecked")
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(row));
        String ch = (String) m.get("channels");
        m.put("channels", ch == null || ch.isEmpty() ? List.of() : List.of(ch.split(",")));
        List<Object> results = Rows.jsonList(row.get("results"));
        Object imagePrompt = null;
        List<Object> shown = new ArrayList<>();
        for (Object o : results) { if (o instanceof Map<?, ?> mm && Boolean.TRUE.equals(mm.get("meta"))) imagePrompt = mm.get("imagePrompt"); else shown.add(o); }
        m.put("results", shown);
        m.put("imagePrompt", imagePrompt);
        return m;
    }

    public Map<String, Object> list(UUID tenantId, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM marketing.posts WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single();
        var rows = jdbc.sql(POST_SELECT + " WHERE p.tenant_id = :t ORDER BY p.created_at DESC LIMIT :lim OFFSET :off").param("t", tenantId).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(rows.stream().map(this::view).toList(), total);
    }

    public record Edit(String caption, List<String> channels, UUID imageFileId, Boolean removeImage) {}

    public Map<String, Object> edit(UUID tenantId, UUID actor, UUID id, Edit e) {
        var cur = get(tenantId, id);
        if (List.of("POSTED", "POSTING").contains(cur.get("status"))) throw BusinessException.conflict("POST_LOCKED", "This post was already published");
        if (e.caption() != null) {
            if (e.caption().length() > 2200) throw BusinessException.badRequest("VALIDATION_ERROR", "The caption is too long");
            jdbc.sql("UPDATE marketing.posts SET caption = :c WHERE id = :i AND tenant_id = :t").param("c", e.caption()).param("i", id).param("t", tenantId).update();
        }
        if (e.channels() != null) {
            List<String> ch = cleanChannels(e.channels(), tenantId);
            jdbc.sql("UPDATE marketing.posts SET channels = CAST(:c AS text[]) WHERE id = :i AND tenant_id = :t").param("c", "{" + String.join(",", ch) + "}").param("i", id).param("t", tenantId).update();
        }
        if (e.imageFileId() != null) {
            files.requireReady(tenantId, e.imageFileId(), java.util.Set.of("PRODUCT_IMAGE"), null);
            jdbc.sql("UPDATE marketing.posts SET image_file_id = :f, image_url = :u WHERE id = :i AND tenant_id = :t").param("f", e.imageFileId()).param("u", publicUrl(tenantId, e.imageFileId())).param("i", id).param("t", tenantId).update();
        }
        if (Boolean.TRUE.equals(e.removeImage())) jdbc.sql("UPDATE marketing.posts SET image_file_id = NULL, image_url = NULL WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        return get(tenantId, id);
    }

    /** New words or a new picture with AI for the same post. */
    public Map<String, Object> regenerate(UUID tenantId, UUID actor, UUID id, String what, String tone) {
        var post = get(tenantId, id);
        if (List.of("POSTED", "POSTING").contains(post.get("status"))) throw BusinessException.conflict("POST_LOCKED", "This post was already published");
        UUID productId = (UUID) post.get("productId");
        AiContentService.Draft d = content.post(subject(tenantId, (String) post.get("kind"), productId), tenantLocale(tenantId), tone, country(tenantId));
        if ("image".equals(what)) return attachAiImage(tenantId, actor, id, d.imagePrompt(), productId);
        jdbc.sql("UPDATE marketing.posts SET caption = :c WHERE id = :i AND tenant_id = :t").param("c", withTags(d.caption(), d.hashtags())).param("i", id).param("t", tenantId).update();
        return get(tenantId, id);
    }

    public void delete(UUID tenantId, UUID id) {
        if (jdbc.sql("DELETE FROM marketing.posts WHERE id = :i AND tenant_id = :t AND status <> 'POSTING'").param("i", id).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found");
    }

    // ---- publishing --------------------------------------------------------------------------------------------------

    public Map<String, Object> schedule(UUID tenantId, UUID actor, UUID id, Instant at) {
        var post = get(tenantId, id);
        if (List.of("POSTED", "POSTING").contains(post.get("status"))) throw BusinessException.conflict("POST_LOCKED", "This post was already published");
        if (at == null || at.isBefore(Instant.now().plusSeconds(60))) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose a time in the future");
        validateReady(tenantId, post);
        jdbc.sql("UPDATE marketing.posts SET status = 'SCHEDULED', scheduled_at = :a WHERE id = :i AND tenant_id = :t").param("a", Timestamp.from(at)).param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "SOCIAL_POST_SCHEDULED", "marketing_post", id, null);
        return get(tenantId, id);
    }

    public Map<String, Object> unschedule(UUID tenantId, UUID id) {
        jdbc.sql("UPDATE marketing.posts SET status = 'DRAFT', scheduled_at = NULL WHERE id = :i AND tenant_id = :t AND status = 'SCHEDULED'").param("i", id).param("t", tenantId).update();
        return get(tenantId, id);
    }

    @SuppressWarnings("unchecked")
    private void validateReady(UUID tenantId, Map<String, Object> post) {
        List<String> chans = (List<String>) post.get("channels");
        if (chans.isEmpty()) throw BusinessException.badRequest("NO_CHANNEL", "Connect a Facebook page or Instagram account first");
        if (chans.contains("INSTAGRAM") && post.get("imageUrl") == null) throw BusinessException.badRequest("IMAGE_REQUIRED", "Instagram posts need a picture");
        if (((String) post.get("caption")).isBlank() && post.get("imageUrl") == null) throw BusinessException.badRequest("VALIDATION_ERROR", "The post is empty");
    }

    /** Posts now. Each channel is tried on its own; the post is POSTED, PARTIAL or FAILED depending on how many went out. */
    public Map<String, Object> publishNow(UUID tenantId, UUID actor, UUID id) {
        var post = get(tenantId, id);
        if ("POSTED".equals(post.get("status"))) throw BusinessException.conflict("POST_LOCKED", "This post was already published");
        validateReady(tenantId, post);
        if (jdbc.sql("UPDATE marketing.posts SET status = 'POSTING' WHERE id = :i AND tenant_id = :t AND status <> 'POSTING'").param("i", id).param("t", tenantId).update() == 0) throw BusinessException.conflict("POST_LOCKED", "This post is being published");
        run(tenantId, id);
        audit.record(actor, tenantId, "SOCIAL_POST_PUBLISHED", "marketing_post", id, null);
        return get(tenantId, id);
    }

    @SuppressWarnings("unchecked")
    private void run(UUID tenantId, UUID id) {
        var post = get(tenantId, id);
        List<String> chans = (List<String>) post.get("channels");
        String caption = (String) post.get("caption");
        String image = (String) post.get("imageUrl");
        List<Object> results = new ArrayList<>();
        for (var meta : Rows.jsonList(jdbc.sql("SELECT results::text FROM marketing.posts WHERE id = :i").param("i", id).query(String.class).single())) if (meta instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("meta"))) results.add(m);
        int ok = 0, total = 0;
        for (var ch : jdbc.sql("SELECT platform, external_id, name, token_enc FROM chat.channels WHERE tenant_id = :t AND status = 'ACTIVE' AND platform IN ('FACEBOOK', 'INSTAGRAM') ORDER BY created_at").param("t", tenantId).query().listOfRows()) {
            String platform = (String) ch.get("platform");
            if (!chans.contains(platform)) continue;
            total++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("channel", platform);
            r.put("name", ch.get("name"));
            try {
                String token = crypto.decrypt((String) ch.get("token_enc"));
                String ext = (String) ch.get("external_id");
                String link = "PRODUCT".equals(post.get("kind")) && post.get("productId") != null ? productLink(tenantId, (UUID) post.get("productId")) : null;
                Map<String, Object> res;
                if ("FACEBOOK".equals(platform)) {
                    String text = link != null && !caption.contains(link) ? caption + "\n\n" + link : caption;
                    res = image != null ? graph.post(ext + "/photos", token, Map.of("url", image, "message", text, "published", true)) : graph.post(ext + "/feed", token, link != null ? Map.of("message", caption, "link", link) : Map.of("message", caption));
                } else {
                    Map<String, Object> container = graph.post(ext + "/media", token, Map.of("image_url", image, "caption", caption));
                    res = graph.post(ext + "/media_publish", token, Map.of("creation_id", String.valueOf(container.get("id"))));
                }
                r.put("ok", true);
                r.put("id", String.valueOf(res.getOrDefault("post_id", res.get("id"))));
                ok++;
            } catch (MetaGraph.MetaException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
            } catch (RuntimeException e) {
                r.put("ok", false);
                r.put("error", "Could not post");
            }
            results.add(r);
        }
        String status = total == 0 ? "FAILED" : ok == total ? "POSTED" : ok == 0 ? "FAILED" : "PARTIAL";
        if (total == 0) results.add(Map.of("channel", "-", "ok", false, "error", "No connected page or account to post to"));
        try {
            jdbc.sql("UPDATE marketing.posts SET status = :s, posted_at = CASE WHEN :ok > 0 THEN now() ELSE posted_at END, results = CAST(:r AS jsonb), scheduled_at = CASE WHEN :s = 'POSTED' THEN scheduled_at ELSE NULL END WHERE id = :i")
                    .param("s", status).param("ok", ok).param("r", JSON.writeValueAsString(results)).param("i", id).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String productLink(UUID tenantId, UUID productId) {
        var slug = jdbc.sql("SELECT slug FROM commerce.products WHERE id = :p AND tenant_id = :t").param("p", productId).param("t", tenantId).query(String.class).optional();
        return slug.map((s) -> urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/products/" + java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)).orElse(null);
    }

    /** Scheduled posts whose time has come. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void publishDue() {
        if (scheduler) runDueNow();
    }

    /** Claims the posts that are due (safe with several instances) and sends them. Public so a test can run it without waiting for the clock. */
    public void runDueNow() {
        var due = jdbc.sql("""
                UPDATE marketing.posts SET status = 'POSTING' WHERE id IN (SELECT id FROM marketing.posts WHERE status = 'SCHEDULED' AND scheduled_at <= now() AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = marketing.posts.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) ORDER BY scheduled_at LIMIT 10 FOR UPDATE SKIP LOCKED)
                RETURNING id, tenant_id
                """).query().listOfRows();
        for (var d : due) {
            try { run((UUID) d.get("tenant_id"), (UUID) d.get("id")); } catch (RuntimeException e) {
                log.warn("Scheduled post failed: {}", e.getMessage());
                jdbc.sql("UPDATE marketing.posts SET status = 'FAILED' WHERE id = :i").param("i", d.get("id")).update();
            }
        }
    }
}

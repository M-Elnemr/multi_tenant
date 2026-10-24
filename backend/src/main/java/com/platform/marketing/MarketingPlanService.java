package com.platform.marketing;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The weekly content plan: one suggested post a day for the next seven days, written from the shop's own best sellers, new arrivals and slow movers. The owner edits and
 * approves each item; approving makes the picture (if the AI can) and schedules the post, and the existing scheduler publishes it. Nothing is posted without that approval.
 */
@Service
public class MarketingPlanService {
    private static final int MONTHLY_IMAGE_CAP = 60;
    private final JdbcClient jdbc;
    private final SocialService social;
    private final ReelService reels;
    private final AuditService audit;

    public MarketingPlanService(JdbcClient jdbc, SocialService social, ReelService reels, AuditService audit) {
        this.jdbc = jdbc;
        this.social = social;
        this.reels = reels;
        this.audit = audit;
    }

    private ZoneId zone(UUID t) {
        try { return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single()); } catch (RuntimeException e) { return ZoneId.of("Africa/Cairo"); }
    }

    private static final String SELECT = """
            SELECT pi.id, pi.day, pi.kind, pi.product_id, p.name AS product_name, pi.topic, pi.caption, array_to_string(pi.channels, ',') AS channels, pi.want_video, pi.video_path IS NOT NULL AS has_video, pi.post_id,
                   CASE WHEN po.status = 'POSTED' THEN 'POSTED' WHEN po.status = 'FAILED' THEN 'FAILED' ELSE pi.status END AS status, po.image_url
            FROM marketing.plan_items pi LEFT JOIN commerce.products p ON p.id = pi.product_id LEFT JOIN marketing.posts po ON po.id = pi.post_id
            """;

    public List<Map<String, Object>> list(UUID t, LocalDate from) {
        LocalDate f = from == null ? LocalDate.now(zone(t)).minusDays(1) : from;
        return jdbc.sql(SELECT + " WHERE pi.tenant_id = :t AND pi.day >= :f ORDER BY pi.day, pi.created_at LIMIT 60").param("t", t).param("f", java.sql.Date.valueOf(f)).query().listOfRows().stream().map(MarketingPlanService::view).toList();
    }

    private Map<String, Object> one(UUID t, UUID id) {
        return view(jdbc.sql(SELECT + " WHERE pi.tenant_id = :t AND pi.id = :i").param("t", t).param("i", id).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan item not found")));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Rows.camel(row));
        String ch = (String) m.get("channels");
        m.put("channels", ch == null || ch.isEmpty() ? List.of() : List.of(ch.split(",")));
        return m;
    }

    /** Fills the next seven days (starting tomorrow) unless that week is already planned. */
    public List<Map<String, Object>> generate(UUID t, UUID actor, String language, boolean wantVideo) {
        LocalDate start = LocalDate.now(zone(t)).plusDays(1);
        if (jdbc.sql("SELECT count(*) FROM marketing.plan_items WHERE tenant_id = :t AND day BETWEEN :a AND :b AND status <> 'SKIPPED'").param("t", t).param("a", java.sql.Date.valueOf(start)).param("b", java.sql.Date.valueOf(start.plusDays(6))).query(Long.class).single() > 0)
            throw BusinessException.conflict("WEEK_PLANNED", "The coming week is already planned");
        // Best sellers first, then newest, then products that have not sold lately (they need the attention most); each product once.
        List<UUID> picks = new ArrayList<>();
        for (String order : List.of("sold_count DESC, created_at DESC", "created_at DESC", "sold_count ASC, created_at ASC"))
            for (UUID id : jdbc.sql("SELECT id FROM commerce.products WHERE tenant_id = :t AND status = 'ACTIVE' ORDER BY " + order + " LIMIT 8").param("t", t).query(UUID.class).list())
                if (!picks.contains(id) && picks.size() < 6) picks.add(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            boolean shop = picks.isEmpty() || i == 6;   // the last day introduces the shop itself
            UUID product = shop ? null : picks.get(i % picks.size());
            Map<String, Object> post = social.draft(t, actor, new SocialService.DraftReq(shop ? "SHOP" : "PRODUCT", product, language, null, false, null));
            UUID itemId = jdbc.sql("INSERT INTO marketing.plan_items (tenant_id, day, kind, product_id, topic, caption, channels, post_id, want_video) VALUES (:t, :d, :k, :p, :tp, :c, CAST(:ch AS text[]), :po, :v) RETURNING id")
                    .param("t", t).param("d", java.sql.Date.valueOf(start.plusDays(i))).param("k", shop ? "SHOP" : "PRODUCT").param("p", product).param("tp", shop ? "Shop" : "")
                    .param("c", String.valueOf(post.get("caption"))).param("ch", "{" + String.join(",", ((List<?>) post.get("channels")).stream().map(String::valueOf).toList()) + "}").param("po", post.get("id")).param("v", wantVideo && !shop).query(UUID.class).single();
            out.add(one(t, itemId));
        }
        audit.record(actor, t, "MARKETING_PLAN_GENERATED", "marketing_plan", null, null);
        return out;
    }

    public record EditReq(String caption, LocalDate day, Boolean wantVideo) {}

    public Map<String, Object> edit(UUID t, UUID actor, UUID id, EditReq r) {
        var item = one(t, id);
        if (!List.of("SUGGESTED", "APPROVED").contains(item.get("status"))) throw BusinessException.conflict("ITEM_LOCKED", "This item is already scheduled or posted");
        if (r.caption() != null) {
            if (r.caption().isBlank() || r.caption().length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "The text must be 1 to 2000 characters");
            jdbc.sql("UPDATE marketing.plan_items SET caption = :c WHERE id = :i AND tenant_id = :t").param("c", r.caption().trim()).param("i", id).param("t", t).update();
            if (item.get("postId") != null) jdbc.sql("UPDATE marketing.posts SET caption = :c WHERE id = :p AND tenant_id = :t").param("c", r.caption().trim()).param("p", item.get("postId")).param("t", t).update();
        }
        if (r.day() != null) jdbc.sql("UPDATE marketing.plan_items SET day = :d WHERE id = :i AND tenant_id = :t").param("d", java.sql.Date.valueOf(r.day())).param("i", id).param("t", t).update();
        if (r.wantVideo() != null) jdbc.sql("UPDATE marketing.plan_items SET want_video = :v WHERE id = :i AND tenant_id = :t").param("v", r.wantVideo()).param("i", id).param("t", t).update();
        return one(t, id);
    }

    /** Approve = make the picture (if possible) and schedule the post for that day at 19:00 shop time (or in five minutes when that has passed). */
    public Map<String, Object> approve(UUID t, UUID actor, UUID id) {
        var item = one(t, id);
        if (!"SUGGESTED".equals(item.get("status"))) throw BusinessException.conflict("ITEM_LOCKED", "Only a suggested item can be approved");
        UUID postId = (UUID) item.get("postId");
        if (postId == null) throw BusinessException.conflict("ITEM_BROKEN", "This item has no post");
        if (item.get("imageUrl") == null && social.imagesAvailable() && imagesThisMonth(t) < MONTHLY_IMAGE_CAP) {
            try { social.attachAiImage(t, actor, postId, item.get("productName") == null ? "A welcoming modern online shop banner, clean colors, square 1:1, no text"
                    : "A clean, bright, professional lifestyle photo of " + item.get("productName") + " on a simple soft background, centered, premium e-commerce style, square 1:1, no text, no logos", (UUID) item.get("productId")); } catch (RuntimeException ignored) { /* a post without a picture can still go out on Facebook */ }
        }
        LocalDate day = ((java.sql.Date) rawDay(t, id)).toLocalDate();
        Instant at = day.atTime(LocalTime.of(19, 0)).atZone(zone(t)).toInstant();
        if (at.isBefore(Instant.now().plusSeconds(300))) at = Instant.now().plusSeconds(300);
        social.schedule(t, actor, postId, at);
        jdbc.sql("UPDATE marketing.plan_items SET status = 'SCHEDULED' WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update();
        audit.record(actor, t, "MARKETING_PLAN_APPROVED", "marketing_plan", id, null);
        return one(t, id);
    }

    private Object rawDay(UUID t, UUID id) { return jdbc.sql("SELECT day FROM marketing.plan_items WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query(java.sql.Date.class).single(); }

    private long imagesThisMonth(UUID t) {
        return jdbc.sql("SELECT count(*) FROM marketing.posts WHERE tenant_id = :t AND image_file_id IS NOT NULL AND created_at >= date_trunc('month', now())").param("t", t).query(Long.class).single();
    }

    public Map<String, Object> skip(UUID t, UUID id) {
        var item = one(t, id);
        if ("SCHEDULED".equals(item.get("status")) && item.get("postId") != null) social.unschedule(t, (UUID) item.get("postId"));
        if (List.of("POSTED").contains(item.get("status"))) throw BusinessException.conflict("ITEM_LOCKED", "Already posted");
        jdbc.sql("UPDATE marketing.plan_items SET status = 'SKIPPED' WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update();
        return one(t, id);
    }

    public boolean videosEnabled() { return reels.enabled(); }

    /** Makes the slideshow video for a product item and remembers where it is. */
    public Map<String, Object> makeVideo(UUID t, UUID id) {
        var item = one(t, id);
        Path file = reels.render(t, id, (UUID) item.get("productId"));
        jdbc.sql("UPDATE marketing.plan_items SET video_path = :p WHERE id = :i AND tenant_id = :t").param("p", file.toString()).param("i", id).param("t", t).update();
        return one(t, id);
    }

    public byte[] video(UUID t, UUID id) {
        String path = jdbc.sql("SELECT video_path FROM marketing.plan_items WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query(String.class).optional().orElse(null);
        if (path == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "No video yet");
        try { return Files.readAllBytes(Path.of(path)); } catch (java.io.IOException e) { throw BusinessException.notFound("RESOURCE_NOT_FOUND", "The video file is gone, make it again"); }
    }
}

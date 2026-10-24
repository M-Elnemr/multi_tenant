package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** A small blog for the shop's own website (buying guides, news). Plain text with blank lines between paragraphs; drafts stay private until published. */
@Service
public class BlogService {
    public record Post(String title, String slug, String excerpt, String body, Boolean published) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public BlogService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    static String slugify(String s) {
        String x = Normalizer.normalize(s == null ? "" : s.trim().toLowerCase(), Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return x.length() > 100 ? x.substring(0, 100) : x;
    }

    public List<Map<String, Object>> publicList(UUID t) {
        return Rows.camel(jdbc.sql("SELECT slug, title, excerpt, published_at FROM commerce.blog_posts WHERE tenant_id = :t AND published ORDER BY published_at DESC LIMIT 100").param("t", t).query().listOfRows());
    }

    public Map<String, Object> publicGet(UUID t, String slug) {
        return Rows.camel(jdbc.sql("SELECT slug, title, excerpt, body, published_at FROM commerce.blog_posts WHERE tenant_id = :t AND slug = :s AND published").param("t", t).param("s", slug).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found")));
    }

    public List<Map<String, Object>> adminList(UUID t) {
        return Rows.camel(jdbc.sql("SELECT id, slug, title, excerpt, published, published_at, updated_at FROM commerce.blog_posts WHERE tenant_id = :t ORDER BY updated_at DESC LIMIT 200").param("t", t).query().listOfRows());
    }

    public Map<String, Object> adminGet(UUID t, UUID id) {
        return Rows.camel(jdbc.sql("SELECT id, slug, title, excerpt, body, published, published_at FROM commerce.blog_posts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found")));
    }

    public Map<String, Object> save(UUID t, UUID actor, UUID id, Post p) {
        String title = p.title() == null ? "" : p.title().trim();
        if (title.length() < 3 || title.length() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "The title must be 3 to 200 characters");
        String body = p.body() == null ? "" : p.body().trim();
        if (body.length() > 50000) throw BusinessException.badRequest("VALIDATION_ERROR", "The post is too long");
        String excerpt = p.excerpt() == null ? "" : p.excerpt().trim();
        if (excerpt.length() > 400) throw BusinessException.badRequest("VALIDATION_ERROR", "The summary is too long");
        String slug = slugify(p.slug() == null || p.slug().isBlank() ? title : p.slug());
        if (slug.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "The address of the post is not valid");
        boolean pub = Boolean.TRUE.equals(p.published());
        if (jdbc.sql("SELECT count(*) FROM commerce.blog_posts WHERE tenant_id = :t AND slug = :s AND (CAST(:i AS uuid) IS NULL OR id <> CAST(:i AS uuid))").param("t", t).param("s", slug).param("i", id).query(Long.class).single() > 0)
            throw BusinessException.conflict("SLUG_TAKEN", "Another post already uses this address");
        if (id == null) id = jdbc.sql("INSERT INTO commerce.blog_posts (tenant_id, slug, title, excerpt, body, published, published_at) VALUES (:t, :s, :ti, :e, :b, :p, CASE WHEN :p THEN now() END) RETURNING id").param("t", t).param("s", slug).param("ti", title).param("e", excerpt).param("b", body).param("p", pub).query(UUID.class).single();
        else if (jdbc.sql("UPDATE commerce.blog_posts SET slug = :s, title = :ti, excerpt = :e, body = :b, published_at = CASE WHEN :p AND NOT published THEN now() WHEN NOT :p THEN NULL ELSE published_at END, published = :p, updated_at = now() WHERE id = :i AND tenant_id = :t")
                .param("s", slug).param("ti", title).param("e", excerpt).param("b", body).param("p", pub).param("i", id).param("t", t).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found");
        audit.record(actor, t, "BLOG_POST_SAVED", "blog_post", id, null);
        return adminGet(t, id);
    }

    public void delete(UUID t, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM commerce.blog_posts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Post not found");
        audit.record(actor, t, "BLOG_POST_DELETED", "blog_post", id, null);
    }
}

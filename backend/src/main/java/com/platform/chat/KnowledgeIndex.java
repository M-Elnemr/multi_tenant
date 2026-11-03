package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What the AI seller knows about each shop, searchable by meaning (RAG). Every active product becomes one document (its names, brand, category, who it is for, description,
 * specs, options, tags, size guide) and every piece of shop text another (about the shop, delivery and return policies, the announcement, each branch). A document is embedded
 * again only when its text changes, a removed product's document goes, and the whole index is kept in step every two minutes, so an owner's edit reaches the seller by itself.
 * <p>
 * Prices and stock are never in these documents: they change all the time, and the seller always reads them from the catalog through its tools. The vectors are searched in memory
 * per shop (exact cosine), which is fast for any shop's catalog and needs nothing new in the database. Without an embedding key all of this is off and the seller searches by
 * words, as before.
 */
@Component
public class KnowledgeIndex {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> AUDIENCE_AR = Map.of("MEN", "رجالي", "WOMEN", "حريمي", "BOYS", "ولادي أطفال", "GIRLS", "بناتي أطفال", "BABY", "بيبي رضع", "ALL", "للكل");

    private final JdbcClient jdbc;
    private final Embedder embedder;
    private final double minScore;
    private final int embedPerRun;

    /** One searchable piece: a product (sourceId = its slug) or a shop text. */
    public record Hit(String type, String sourceId, String title, String body, double score) {}

    record Doc(String type, String sourceId, int chunk, String title, String body) {}

    private record Row(String type, String sourceId, String title, String body, float[] vec) {}

    private record Loaded(String version, List<Row> rows) {}

    private final Map<UUID, Loaded> vectors = new ConcurrentHashMap<>();
    private final Map<String, float[]> queryCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> e) { return size() > 500; }
    });

    /** The same words searched again in the same shop (search_products then shop_knowledge, or the next customer): no second call to the provider, until the index changes. */
    private final Map<String, List<Hit>> hitCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Hit>> e) { return size() > 400; }
    });
    /** Each shop's catalog fingerprint when its index was last complete: an unchanged shop is not read again until the half-hourly sweep. */
    private final Map<UUID, String> synced = new ConcurrentHashMap<>();
    private volatile long nextSweep = 0;

    public KnowledgeIndex(JdbcClient jdbc, Embedder embedder, @Value("${app.ai.embed.min-score:0.3}") double minScore, @Value("${app.ai.embed.per-run:300}") int embedPerRun) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.minScore = minScore;
        this.embedPerRun = embedPerRun;
    }

    public boolean enabled() { return embedder.enabled(); }

    // ---- keeping the index in step -------------------------------------------------------------------------------------------

    /**
     * Every two minutes: each shop whose catalog or texts changed since its index was last complete is brought in step (a new shop is simply a shop that changed), and every half
     * hour all shops are, whatever changed (an edit that went around the services, a model change). A shop that no longer sells anything leaves the index.
     */
    @Scheduled(fixedDelayString = "${app.ai.embed.sync-every:PT2M}", initialDelayString = "PT70S")
    public void scheduled() {
        // on its own thread: the first fill of a big catalog takes minutes, and the app's timers (reports, reminders, backups) share one thread
        if (running.compareAndSet(false, true))
            worker.execute(() -> { try { syncAll(); } catch (RuntimeException e) { log.warn("Knowledge index run failed: {}", e.getMessage()); } finally { running.set(false); } });
    }

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-index");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    void stop() { worker.shutdownNow(); }

    /** One pass over every shop (see {@link #scheduled()}); public for tests. */
    public void syncAll() {
        if (!embedder.enabled()) return;
        boolean sweep = System.currentTimeMillis() >= nextSweep;
        if (sweep) nextSweep = System.currentTimeMillis() + 30 * 60_000L;
        Set<UUID> selling = new java.util.HashSet<>(jdbc.sql("SELECT DISTINCT tenant_id FROM commerce.products WHERE status = 'ACTIVE'").query(UUID.class).list());
        for (UUID gone : jdbc.sql("SELECT DISTINCT tenant_id FROM chat.knowledge_chunks").query(UUID.class).list())
            if (!selling.contains(gone)) { jdbc.sql("DELETE FROM chat.knowledge_chunks WHERE tenant_id = :t").param("t", gone).update(); vectors.remove(gone); synced.remove(gone); }
        Map<UUID, String> prints = fingerprints();
        int shops = 0, embedded = 0;
        for (UUID t : selling) {
            String print = prints.getOrDefault(t, "") + "|" + embedder.model();
            if (!sweep && print.equals(synced.get(t))) continue;
            try {
                embedded += sync(t);
                shops++;
                if (complete(t)) synced.put(t, print); else synced.remove(t);
            } catch (RuntimeException e) {
                synced.remove(t);
                log.warn("Knowledge index of {} not synced: {}", t, e.getMessage());
            }
            if (!embedder.enabled()) break;   // the provider refused or is down: the next run tries again
        }
        if (embedded > 0) log.info("Knowledge index: {} shops checked, {} documents embedded", shops, embedded);
    }

    /** One short text per shop that changes whenever anything its documents are made of changes: products, their options and values, other names, the shop's texts, branches. */
    private Map<UUID, String> fingerprints() {
        Map<UUID, String> out = new HashMap<>();
        jdbc.sql("""
                SELECT t, md5(string_agg(x, '|' ORDER BY x)) AS fp FROM (
                  SELECT tenant_id AS t, id::text || status || updated_at::text AS x FROM commerce.products
                  UNION ALL SELECT p.tenant_id, o.id::text || o.name || o.sort_order FROM commerce.product_options o JOIN commerce.products p ON p.id = o.product_id
                  UNION ALL SELECT p.tenant_id, v.id::text || v.value || v.sort_order FROM commerce.product_option_values v JOIN commerce.product_options o ON o.id = v.option_id JOIN commerce.products p ON p.id = o.product_id
                  UNION ALL SELECT p.tenant_id, tr.product_id::text || tr.locale || tr.name FROM commerce.product_translations tr JOIN commerce.products p ON p.id = tr.product_id
                  UNION ALL SELECT tenant_id, 'profile ' || updated_at::text FROM commerce.store_profiles
                  UNION ALL SELECT tenant_id, id::text || is_active || updated_at::text FROM commerce.branches
                ) z GROUP BY t
                """).query((rs, n) -> out.put(rs.getObject("t", UUID.class), rs.getString("fp"))).list();
        return out;
    }

    /** True when every document is embedded with the current model (a big new catalog is embedded over a few runs). */
    private boolean complete(UUID tenantId) {
        return jdbc.sql("SELECT count(*) = 0 FROM chat.knowledge_chunks WHERE tenant_id = :t AND (embedding IS NULL OR embed_model IS DISTINCT FROM :m)")
                .param("t", tenantId).param("m", embedder.model()).query(Boolean.class).single();
    }

    /** Brings one shop's documents in step with its catalog and texts, then embeds what is new or changed. Returns how many documents were embedded. */
    public int sync(UUID tenantId) {
        synchronized (locks.computeIfAbsent(tenantId, k -> new Object())) { return syncNow(tenantId); }   // the schedule and the reindex button never write one shop at once
    }

    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    private int syncNow(UUID tenantId) {
        List<Doc> docs = documents(tenantId);
        Map<String, String> stored = new HashMap<>();
        for (var r : jdbc.sql("SELECT source_type, source_id, chunk_no, content_hash FROM chat.knowledge_chunks WHERE tenant_id = :t").param("t", tenantId).query().listOfRows())
            stored.put(r.get("source_type") + "|" + r.get("source_id") + "|" + r.get("chunk_no"), String.valueOf(r.get("content_hash")));
        Set<String> keep = new java.util.HashSet<>();
        for (Doc d : docs) {
            String key = d.type() + "|" + d.sourceId() + "|" + d.chunk(), hash = sha(embedder.model() + "\n" + d.title() + "\n" + d.body());
            keep.add(key);
            if (hash.equals(stored.get(key))) continue;
            jdbc.sql("""
                    INSERT INTO chat.knowledge_chunks (tenant_id, source_type, source_id, chunk_no, title, body, content_hash, embedding, embed_model, updated_at)
                    VALUES (:t, :ty, :s, :c, :ti, :b, :h, NULL, NULL, now())
                    ON CONFLICT (tenant_id, source_type, source_id, chunk_no) DO UPDATE SET title = :ti, body = :b, content_hash = :h, embedding = NULL, embed_model = NULL, updated_at = now()
                    """).param("t", tenantId).param("ty", d.type()).param("s", d.sourceId()).param("c", d.chunk()).param("ti", d.title()).param("b", d.body()).param("h", hash).update();
        }
        for (String key : stored.keySet())
            if (!keep.contains(key)) {
                String[] k = key.split("\\|", 3);
                jdbc.sql("DELETE FROM chat.knowledge_chunks WHERE tenant_id = :t AND source_type = :ty AND source_id = :s AND chunk_no = :c")
                        .param("t", tenantId).param("ty", k[0]).param("s", k[1]).param("c", Integer.parseInt(k[2])).update();
            }
        return embedPending(tenantId);
    }

    private int embedPending(UUID tenantId) {
        var rows = jdbc.sql("SELECT id, title, body FROM chat.knowledge_chunks WHERE tenant_id = :t AND embedding IS NULL ORDER BY id LIMIT :n").param("t", tenantId).param("n", embedPerRun).query().listOfRows();
        if (rows.isEmpty()) return 0;
        List<String> texts = rows.stream().map(r -> r.get("title") + "\n" + r.get("body")).toList();
        float[][] vs = embedder.embed(texts);
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder lit = new StringBuilder("{");   // a Postgres array literal: «{0.012,-0.3,…}»
            for (int k = 0; k < vs[i].length; k++) lit.append(k == 0 ? "" : ",").append(vs[i][k]);
            jdbc.sql("UPDATE chat.knowledge_chunks SET embedding = CAST(:e AS real[]), embed_model = :m, updated_at = now() WHERE id = :i")   // a new version: searches stop using the old vector
                    .param("e", lit.append('}').toString()).param("m", embedder.model()).param("i", rows.get(i).get("id")).update();
        }
        vectors.remove(tenantId);
        return rows.size();
    }

    // ---- the documents --------------------------------------------------------------------------------------------------------

    /** Every document of the shop, from its own data. Package-private for tests. */
    List<Doc> documents(UUID tenantId) {
        List<Doc> out = new ArrayList<>();
        for (var p : jdbc.sql("""
                SELECT p.slug, p.name, p.brand, p.short_description, p.description, p.specs::text AS specs, array_to_string(p.tags, '، ') AS tags, p.size_guide, p.audience, p.item_condition,
                  (SELECT string_agg(a.name_ar || ' / ' || a.name_en, ' › ' ORDER BY a.level) FROM commerce.taxonomy a JOIN commerce.taxonomy t ON t.id = p.taxonomy_id AND t.path LIKE a.path || '%') AS category,
                  (SELECT string_agg(o.name || ': ' || (SELECT string_agg(v.value, '، ' ORDER BY v.sort_order) FROM commerce.product_option_values v WHERE v.option_id = o.id), ' | ' ORDER BY o.sort_order)
                     FROM commerce.product_options o WHERE o.product_id = p.id) AS options,
                  (SELECT string_agg(tr.name, ' / ') FROM commerce.product_translations tr WHERE tr.product_id = p.id) AS other_names
                FROM commerce.products p WHERE p.tenant_id = :t AND p.status = 'ACTIVE' ORDER BY p.created_at
                """).param("t", tenantId).query().listOfRows())
            out.add(new Doc("PRODUCT", String.valueOf(p.get("slug")), 0, String.valueOf(p.get("name")), productText(p)));
        var s = jdbc.sql("SELECT announcement, shipping_policy, return_policy, short_description FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(Map.of());
        shopText(out, "about", "عن المحل / About the shop", s.get("short_description"));
        shopText(out, "announcement", "إعلان المحل والعروض / Announcement", s.get("announcement"));
        shopText(out, "shipping_policy", "سياسة التوصيل والشحن / Delivery policy", s.get("shipping_policy"));
        shopText(out, "return_policy", "سياسة الاسترجاع والاستبدال / Return and exchange policy", s.get("return_policy"));
        for (var b : jdbc.sql("SELECT id, name, address_line1, area, city, landmark, is_pickup FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY sort_order LIMIT 20").param("t", tenantId).query().listOfRows()) {
            String where = String.join("، ", java.util.stream.Stream.of(b.get("address_line1"), b.get("area"), b.get("city"), b.get("landmark")).filter(x -> x != null && !x.toString().isBlank()).map(String::valueOf).toList());
            shopText(out, "branch-" + b.get("id"), "فرع " + b.get("name") + " / Branch", where + (Boolean.TRUE.equals(b.get("is_pickup")) ? "\nممكن تستلم طلبك من الفرع ده من غير مصاريف شحن." : ""));
        }
        return out;
    }

    private static String productText(Map<String, Object> p) {
        StringBuilder b = new StringBuilder();
        line(b, null, p.get("other_names") == null ? null : "أسماء تانية: " + p.get("other_names"));
        line(b, "الماركة", p.get("brand"));
        line(b, "الفئة", p.get("category"));
        String aud = p.get("audience") == null ? null : AUDIENCE_AR.get(String.valueOf(p.get("audience")));
        line(b, "لمين", aud);
        if (p.get("item_condition") != null && !"NEW".equals(p.get("item_condition"))) line(b, "الحالة", p.get("item_condition"));
        line(b, null, p.get("short_description"));
        line(b, null, clip(p.get("description"), 1500));
        List<String> specs = new ArrayList<>();
        try {
            if (p.get("specs") != null) for (JsonNode n : JSON.readTree(p.get("specs").toString())) if (!n.path("k").asText().isBlank()) specs.add(n.path("k").asText() + ": " + n.path("v").asText());
        } catch (Exception e) { log.debug("Specs skipped", e); }
        if (!specs.isEmpty()) line(b, "المواصفات", String.join("؛ ", specs));
        line(b, "الاختيارات", p.get("options"));
        line(b, "كلمات", p.get("tags"));
        line(b, "دليل المقاسات", clip(p.get("size_guide"), 400));
        return b.toString().trim();
    }

    /** Long shop texts in pieces of about 800 characters, cut at paragraph or sentence ends, so each piece is one topic. */
    private static void shopText(List<Doc> out, String id, String title, Object text) {
        if (text == null || text.toString().isBlank()) return;
        String t = text.toString().trim();
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : t.split("(?<=[.!؟?\\n])\\s+")) {
            if (cur.length() + para.length() > 800 && !cur.isEmpty()) { parts.add(cur.toString().trim()); cur.setLength(0); }
            cur.append(para).append(' ');
        }
        if (!cur.isEmpty()) parts.add(cur.toString().trim());
        for (int i = 0; i < parts.size(); i++) out.add(new Doc("SHOP", id, i, title, parts.get(i)));
    }

    private static void line(StringBuilder b, String label, Object v) {
        if (v == null || v.toString().isBlank()) return;
        b.append(label == null ? "" : label + ": ").append(v.toString().replaceAll("\\s+", " ").trim()).append('\n');
    }

    private static String clip(Object o, int n) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ---- search by meaning --------------------------------------------------------------------------------------------------

    /**
     * The shop's pieces closest in meaning to the words, best first: up to {@code k}, only those near the best one and above the floor (so a question about nothing the shop has
     * returns nothing rather than the least bad match). {@code type} PRODUCT, SHOP or null for both. Empty when off or when the provider fails (the caller searches by words).
     */
    public List<Hit> search(UUID tenantId, String query, String type, int k) {
        if (!embedder.enabled() || query == null || query.isBlank()) return List.of();
        try {
            Loaded loaded = loaded(tenantId);
            List<Row> rows = loaded.rows();
            if (rows.isEmpty()) return List.of();
            String cacheKey = tenantId + "|" + loaded.version() + "|" + type + "|" + k + "|" + FastAnswers.norm(query);
            List<Hit> cached = hitCache.get(cacheKey);
            if (cached != null) return cached;
            float[] q = queryVector(query);
            List<Hit> hits = new ArrayList<>();
            for (Row r : rows) {
                if (type != null && !type.equals(r.type())) continue;
                if (r.vec().length != q.length) continue;
                double s = 0;
                for (int i = 0; i < q.length; i++) s += q[i] * r.vec()[i];
                hits.add(new Hit(r.type(), r.sourceId(), r.title(), r.body(), s));
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            if (hits.isEmpty() || hits.get(0).score() < minScore) return List.of();
            double floor = Math.max(minScore, hits.get(0).score() - 0.15);
            List<Hit> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Hit h : hits) {
                if (h.score() < floor || out.size() >= k) break;
                if (seen.add(h.type() + "|" + h.sourceId())) out.add(h);   // a long shop text in pieces counts once, by its best piece
            }
            List<Hit> result = List.copyOf(out);
            hitCache.put(cacheKey, result);
            return result;
        } catch (RuntimeException e) {
            log.warn("Search by meaning failed, words only: {}", e.getMessage());
            return List.of();
        }
    }

    /** The products most like this one (from its stored vector, no call to the provider), best first; empty when off or not indexed yet. */
    public List<String> similarProducts(UUID tenantId, String slug, int k) {
        if (!embedder.enabled() || slug == null) return List.of();
        try {
            List<Row> rows = rows(tenantId);
            Row me = rows.stream().filter(r -> "PRODUCT".equals(r.type()) && r.sourceId().equals(slug)).findFirst().orElse(null);
            if (me == null) return List.of();
            return rows.stream().filter(r -> "PRODUCT".equals(r.type()) && !r.sourceId().equals(slug) && r.vec().length == me.vec().length)
                    .map(r -> { double s = 0; for (int i = 0; i < r.vec().length; i++) s += r.vec()[i] * me.vec()[i]; return Map.entry(r.sourceId(), s); })
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(k).map(Map.Entry::getKey).toList();
        } catch (RuntimeException e) {
            log.warn("Similar products failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** The slugs of the products closest in meaning, best first. */
    public List<String> productSlugs(UUID tenantId, String query, int k) {
        return search(tenantId, query, "PRODUCT", k).stream().map(Hit::sourceId).toList();
    }

    private float[] queryVector(String query) {
        String key = FastAnswers.norm(query);
        float[] v = queryCache.get(key);
        if (v == null) {
            v = embedder.embedQuery(query);
            queryCache.put(key, v);
        }
        return v;
    }

    private List<Row> rows(UUID tenantId) { return loaded(tenantId).rows(); }

    private Loaded loaded(UUID tenantId) {
        String version = jdbc.sql("SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') || ':' || coalesce(max(id), 0) FROM chat.knowledge_chunks WHERE tenant_id = :t AND embedding IS NOT NULL")
                .param("t", tenantId).query(String.class).single();
        Loaded l = vectors.get(tenantId);
        if (l != null && l.version().equals(version)) return l;
        List<Row> rows = new ArrayList<>();
        jdbc.sql("SELECT source_type, source_id, title, body, embedding FROM chat.knowledge_chunks WHERE tenant_id = :t AND embedding IS NOT NULL AND embed_model = :m")
                .param("t", tenantId).param("m", embedder.model())
                .query((rs, n) -> {
                    Object[] arr = (Object[]) rs.getArray("embedding").getArray();
                    float[] v = new float[arr.length];
                    for (int i = 0; i < arr.length; i++) v[i] = ((Number) arr[i]).floatValue();
                    rows.add(new Row(rs.getString("source_type"), rs.getString("source_id"), rs.getString("title"), rs.getString("body"), v));
                    return null;
                }).list();
        Loaded fresh = new Loaded(version, List.copyOf(rows));
        vectors.put(tenantId, fresh);
        return fresh;
    }

    /** How far the shop's index is: documents, embedded ones, the model. For the readiness page and the logs. */
    public Map<String, Object> status(UUID tenantId) {
        var r = jdbc.sql("SELECT count(*) AS docs, count(embedding) AS embedded, count(*) FILTER (WHERE source_type = 'PRODUCT') AS products FROM chat.knowledge_chunks WHERE tenant_id = :t")
                .param("t", tenantId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", embedder.enabled());
        m.put("model", embedder.enabled() ? embedder.model() : null);
        m.put("documents", r.get("docs"));
        m.put("embedded", r.get("embedded"));
        m.put("products", r.get("products"));
        return m;
    }

    /** Every selling shop's index, least complete first: how many products it sells, how many are indexed and embedded. For the platform's AI health page. */
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", embedder.enabled());
        m.put("model", embedder.model());
        m.put("shops", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT t.id AS tenant_id, t.name, t.slug,
                       (SELECT count(*) FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS selling,
                       count(k.id) FILTER (WHERE k.source_type = 'PRODUCT') AS indexed,
                       count(k.id) FILTER (WHERE k.source_type = 'PRODUCT' AND k.embedding IS NOT NULL AND k.embed_model = :m) AS embedded,
                       count(k.id) FILTER (WHERE k.source_type = 'SHOP') AS shop_texts, max(k.updated_at) AS updated_at
                FROM core.tenants t LEFT JOIN chat.knowledge_chunks k ON k.tenant_id = t.id
                WHERE EXISTS (SELECT 1 FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE')
                GROUP BY t.id, t.name, t.slug
                ORDER BY (count(k.id) FILTER (WHERE k.source_type = 'PRODUCT' AND k.embedding IS NOT NULL AND k.embed_model = :m))::float
                         / greatest(1, (SELECT count(*) FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE')), t.name
                LIMIT 200
                """).param("m", embedder.model()).query().listOfRows()));
        return m;
    }

    /** Brings one shop's index in step now (the platform's "reindex" button), whatever its fingerprint says. */
    public Map<String, Object> reindexNow(UUID tenantId) {
        synced.remove(tenantId);
        int n = sync(tenantId);
        Map<String, Object> m = status(tenantId);
        m.put("embeddedNow", n);
        return m;
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

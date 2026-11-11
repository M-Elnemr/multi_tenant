package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Hive API keys the platform spreads its requests over: one account allows about 5 requests a second to each model, so every extra key adds that much room. Requests take the
 * enabled keys in turn; a key that answers 429 (too many requests) or a server error rests 30 seconds, one that is refused (401 / 403) rests 5 minutes, and the request goes on another key.
 * The keys are those of the server environment (GLM_API_KEY, DEEPSEEK_API_KEY) and those the platform admin added in the AI configuration screen (stored encrypted, never shown in full).
 */
@Component
public class HiveKeyPool {
    private static final Logger log = LoggerFactory.getLogger(HiveKeyPool.class);

    /** One key lent for one request. {@code id} is what the admin screen shows and what is excluded when a twin should use another key. */
    public record Lease(String id, String key) {}

    private static final class Entry {
        final String id, label, key, last4;
        final boolean fromEnv;
        volatile boolean enabled;
        volatile long restUntil;
        final AtomicLong requests = new AtomicLong(), failures = new AtomicLong();
        volatile String lastStatus = "";
        volatile long lastAt;

        Entry(String id, String label, String key, boolean enabled, boolean fromEnv) {
            this.id = id; this.label = label; this.key = key; this.enabled = enabled; this.fromEnv = fromEnv;
            this.last4 = key.length() <= 4 ? key : key.substring(key.length() - 4);
        }
    }

    private final JdbcClient jdbc;
    private final TokenCrypto crypto;
    private final List<String> envKeys;
    private volatile List<Entry> entries = List.of();
    private volatile long loadedAt = 0;
    private final AtomicInteger next = new AtomicInteger();
    /** Seconds a key rests after 429 / 5xx, and after 401 / 403. */
    volatile long restSeconds = 30, refusedRestSeconds = 300;
    private static final long RELOAD_MS = 20_000;

    @Autowired
    public HiveKeyPool(JdbcClient jdbc, TokenCrypto crypto, @Value("${app.ai.glm.api-key:}") String glmKey, @Value("${app.ai.deepseek.api-key:}") String deepseekKey) {
        this(jdbc, crypto, distinct(glmKey, deepseekKey));
    }

    private HiveKeyPool(JdbcClient jdbc, TokenCrypto crypto, List<String> envKeys) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.envKeys = envKeys;
    }

    /** For tests and for a service without a database: exactly these keys. */
    public static HiveKeyPool of(String... keys) { return new HiveKeyPool(null, null, distinct(keys)); }

    private static List<String> distinct(String... keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) if (k != null && !k.isBlank() && !out.contains(k.trim())) out.add(k.trim());
        return out;
    }

    // ---- loading ----------------------------------------------------------------------------------------------------------------

    private List<Entry> entries() {
        if (System.currentTimeMillis() - loadedAt < RELOAD_MS) return entries;
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < RELOAD_MS) return entries;
            Map<String, Entry> old = new LinkedHashMap<>();
            for (Entry e : entries) old.put(e.id, e);
            List<Entry> fresh = new ArrayList<>();
            for (int i = 0; i < envKeys.size(); i++) fresh.add(keep(old, new Entry("env-" + (i + 1), "Server key " + (i + 1), envKeys.get(i), true, true)));
            if (jdbc != null) {
                try {
                    jdbc.sql("SELECT id, label, key_enc, enabled FROM chat.platform_ai_keys WHERE provider = 'hive' ORDER BY created_at, id").query((rs, n) -> {
                        try { fresh.add(keep(old, new Entry(rs.getString("id"), rs.getString("label"), crypto.decrypt(rs.getString("key_enc")), rs.getBoolean("enabled"), false))); }
                        catch (RuntimeException e) { log.warn("A stored AI key could not be read and is skipped: {}", e.getMessage()); }
                        return null;
                    }).list();
                } catch (RuntimeException e) { log.warn("AI keys not loaded from the database, using the server's: {}", e.getMessage()); }
            }
            entries = List.copyOf(fresh);
            loadedAt = System.currentTimeMillis();
            return entries;
        }
    }

    /** A reloaded key keeps what was counted for it and its rest. */
    private static Entry keep(Map<String, Entry> old, Entry fresh) {
        Entry o = old.get(fresh.id);
        if (o == null || !o.key.equals(fresh.key)) return fresh;
        fresh.restUntil = o.restUntil; fresh.lastStatus = o.lastStatus; fresh.lastAt = o.lastAt;
        fresh.requests.set(o.requests.get()); fresh.failures.set(o.failures.get());
        return fresh;
    }

    /** Forget what was loaded: the next request reads the keys again (after the admin added, removed or switched one). */
    public void reload() { loadedAt = 0; }

    // ---- lending ----------------------------------------------------------------------------------------------------------------

    /** True when at least one key is enabled (resting keys count: they come back). */
    public boolean hasKeys() { return entries().stream().anyMatch(e -> e.enabled); }

    /** How many keys are enabled: the number the request rate is multiplied by. */
    public int size() { return (int) entries().stream().filter(e -> e.enabled).count(); }

    /**
     * The next key in turn, not resting and not {@code excludeId} when another one is free; otherwise a resting or the excluded one (a request that has no other key still goes, and the
     * provider decides). Null only when no key is enabled at all.
     */
    public Lease acquire(String excludeId) {
        List<Entry> all = entries().stream().filter(e -> e.enabled).toList();
        if (all.isEmpty()) return null;
        long now = System.currentTimeMillis();
        int start = Math.floorMod(next.getAndIncrement(), all.size());
        Entry pickedResting = null, pickedExcluded = null;
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get((start + i) % all.size());
            boolean resting = e.restUntil > now, excluded = e.id.equals(excludeId);
            if (!resting && !excluded) return lend(e);
            if (excluded && pickedExcluded == null) pickedExcluded = e;
            if (resting && pickedResting == null) pickedResting = e;
        }
        return lend(pickedExcluded != null && (pickedResting == null || pickedExcluded.restUntil <= now) ? pickedExcluded : pickedResting != null ? pickedResting : pickedExcluded);
    }

    private Lease lend(Entry e) { e.requests.incrementAndGet(); e.lastAt = System.currentTimeMillis(); return new Lease(e.id, e.key); }

    public void ok(Lease l) { Entry e = find(l); if (e != null) e.lastStatus = "ok"; }

    /** The service answered {@code status} (0 = no answer): 429, 5xx and no answer rest the key briefly, 401 / 403 for longer; other statuses are the request's own fault. */
    public void failed(Lease l, int status) {
        Entry e = find(l);
        if (e == null) return;
        e.failures.incrementAndGet();
        e.lastStatus = status == 0 ? "no answer" : "HTTP " + status;
        if (status == 401 || status == 403) e.restUntil = System.currentTimeMillis() + refusedRestSeconds * 1000;
        else if (status == 0 || status == 429 || status == 408 || status / 100 == 5) e.restUntil = System.currentTimeMillis() + restSeconds * 1000;
    }

    private Entry find(Lease l) { if (l == null) return null; for (Entry e : entries) if (e.id.equals(l.id())) return e; return null; }

    // ---- what the admin screen shows and changes ---------------------------------------------------------------------------------

    /** The keys as the admin sees them: never the key itself, only its last four characters. */
    public List<Map<String, Object>> view() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id); m.put("label", e.label); m.put("last4", e.last4); m.put("fromEnvironment", e.fromEnv); m.put("enabled", e.enabled);
            m.put("requests", e.requests.get()); m.put("failures", e.failures.get()); m.put("lastStatus", e.lastStatus);
            m.put("restingSeconds", Math.max(0, (e.restUntil - now) / 1000));
            out.add(m);
        }
        return out;
    }

    /** The key itself, for the admin's "test" button only (never returned by any endpoint). */
    Lease lease(String id) { for (Entry e : entries()) if (e.id.equals(id)) return new Lease(e.id, e.key); return null; }

    public UUID add(String label, String key, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO chat.platform_ai_keys (id, label, key_enc, last4, created_by) VALUES (:i, :l, :k, :s, :b)").param("i", id).param("l", label).param("k", crypto.encrypt(key))
                .param("s", key.length() <= 4 ? key : key.substring(key.length() - 4)).param("b", by).update();
        reload();
        return id;
    }

    public boolean setEnabled(String id, boolean on) {
        if (id.startsWith("env-")) return false;   // the server's own keys are changed in its environment, not here
        int n = jdbc.sql("UPDATE chat.platform_ai_keys SET enabled = :e WHERE id = CAST(:i AS uuid)").param("e", on).param("i", id).update();
        reload();
        return n > 0;
    }

    public boolean delete(String id) {
        if (id.startsWith("env-")) return false;
        int n = jdbc.sql("DELETE FROM chat.platform_ai_keys WHERE id = CAST(:i AS uuid)").param("i", id).update();
        reload();
        return n > 0;
    }
}

package com.platform.chat.unofficial;

import com.platform.shared.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Imports the Page's existing Messenger contacts through the shop's unofficial session, so a Page connected only by cookies (no official channel) still has "existing customers".
 * One chat.conversations row per contact (channel FACEBOOK, external_id = the contact's user id, origin DIRECT, imported_at set, no messages). The schema needs no chat.channels row.
 * The last activity is capped to 25 hours ago: it also counts the Page's own replies, so it must never make an imported contact look "inside the 24 h window" (that would send it by the official route).
 * Idempotent through the unique key (tenant, channel, external_id). One import at a time per shop, paced between bridge calls. Nothing is billed.
 */
@Service
public class MessengerContactImport {
    private static final Logger log = LoggerFactory.getLogger(MessengerContactImport.class);
    private static final String PLATFORM = "FACEBOOK";

    private final JdbcClient jdbc;
    private final UnofficialMetaService unofficial;
    private final boolean async, scheduler;
    private final int pageSize, maxContacts, sleepMin, sleepMax;
    private final Random rnd = new Random();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "messenger-import"); t.setDaemon(true); return t; });

    public MessengerContactImport(JdbcClient jdbc, UnofficialMetaService unofficial,
                                  @Value("${app.contact-import.async:true}") boolean async, @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler,
                                  @Value("${app.contact-import.page-size:100}") int pageSize, @Value("${app.contact-import.max-contacts:5000}") int maxContacts,
                                  @Value("${app.contact-import.page-sleep-min-ms:2500}") int sleepMin, @Value("${app.contact-import.page-sleep-max-ms:6000}") int sleepMax) {
        this.jdbc = jdbc;
        this.unofficial = unofficial;
        this.async = async;
        this.scheduler = scheduler;
        this.pageSize = Math.max(1, Math.min(500, pageSize));
        this.maxContacts = Math.max(1, maxContacts);
        this.sleepMin = Math.max(0, sleepMin);
        this.sleepMax = Math.max(this.sleepMin, sleepMax);
    }

    private static String platform(String p) {
        if (!PLATFORM.equals(p == null ? "" : p.trim().toUpperCase())) throw BusinessException.badRequest("UNSUPPORTED_PLATFORM", "Only Messenger contacts can be imported");
        return PLATFORM;
    }

    /** Starts an import (or returns the one already running for this shop: one at a time). */
    public synchronized Map<String, Object> start(UUID tenantId, String platformIn) {
        String platform = platform(platformIn);
        if (!"ACTIVE".equals(unofficial.status(tenantId, platform))) throw BusinessException.conflict("NEEDS_LOGIN", "Connect Messenger first");
        jdbc.sql("UPDATE chat.contact_imports SET status = 'FAILED', error = 'INTERRUPTED', finished_at = now() WHERE tenant_id = :t AND status = 'RUNNING' AND started_at < now() - interval '60 minutes'").param("t", tenantId).update();
        var running = jdbc.sql("SELECT id FROM chat.contact_imports WHERE tenant_id = :t AND status = 'RUNNING' ORDER BY started_at DESC LIMIT 1").param("t", tenantId).query(UUID.class).optional();
        if (running.isPresent()) return Map.of("importId", running.get(), "status", "RUNNING");
        boolean incremental = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.contact_imports WHERE tenant_id = :t AND platform = :p AND status = 'DONE')").param("t", tenantId).param("p", platform).query(Boolean.class).single();
        UUID id = jdbc.sql("INSERT INTO chat.contact_imports (tenant_id, platform) VALUES (:t, :p) RETURNING id").param("t", tenantId).param("p", platform).query(UUID.class).single();
        if (async) pool.submit(() -> run(id, tenantId, platform, incremental));
        else run(id, tenantId, platform, incremental);
        String st = jdbc.sql("SELECT status FROM chat.contact_imports WHERE id = :i").param("i", id).query(String.class).single();
        return Map.of("importId", id, "status", st);
    }

    /** Fire and forget (right after a connect): never throws, never blocks the caller beyond the insert. */
    public void startQuietly(UUID tenantId, String platform) {
        try { start(tenantId, platform); } catch (RuntimeException e) { log.debug("Messenger contact import not started: {}", e.getMessage()); }
    }

    public Map<String, Object> status(UUID tenantId, String platformIn) {
        String platform = platform(platformIn);
        var last = jdbc.sql("SELECT status, fetched, imported, error, started_at, finished_at FROM chat.contact_imports WHERE tenant_id = :t AND platform = :p ORDER BY started_at DESC LIMIT 1")
                .param("t", tenantId).param("p", platform).query().listOfRows().stream().findFirst().orElse(null);
        Instant lastDone = jdbc.sql("SELECT max(finished_at) FROM chat.contact_imports WHERE tenant_id = :t AND platform = :p AND status = 'DONE'").param("t", tenantId).param("p", platform)
                .query((rs, i) -> rs.getTimestamp(1)).optional().map(Timestamp::toInstant).orElse(null);
        long contacts = jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND channel = 'FACEBOOK'").param("t", tenantId).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", last == null ? "IDLE" : last.get("status"));
        out.put("fetched", last == null ? 0 : last.get("fetched"));
        out.put("imported", last == null ? 0 : last.get("imported"));
        out.put("error", last == null ? null : last.get("error"));
        out.put("startedAt", last == null ? null : inst(last.get("started_at")));
        out.put("finishedAt", last == null ? null : inst(last.get("finished_at")));
        out.put("lastImportedAt", lastDone);
        out.put("contacts", contacts);
        return out;
    }

    private static Instant inst(Object o) { return o instanceof Timestamp t ? t.toInstant() : null; }

    void run(UUID importId, UUID tenantId, String platform, boolean incremental) {
        int fetched = 0, added = 0;
        String error = null;
        try {
            String before = null;
            OutOfWindowSender sender = unofficial.transport(platform);
            if (sender == null) throw new IllegalStateException("no sender");
            while (fetched < maxContacts) {
                var page = sender.listThreads(tenantId, platform, Math.min(pageSize, maxContacts - fetched), before);
                if (!page.ok()) { error = page.error() == null ? "FAILED" : page.error(); break; }
                int newOnPage = 0;
                for (var t : page.threads()) {
                    if (upsert(tenantId, t)) newOnPage++;
                }
                fetched += page.threads().size();
                added += newOnPage;
                jdbc.sql("UPDATE chat.contact_imports SET fetched = :f, imported = :i WHERE id = :id").param("f", fetched).param("i", added).param("id", importId).update();
                before = page.nextBefore();
                if (before == null || page.threads().isEmpty()) break;
                if (incremental && newOnPage == 0) break;   // the daily run only needs the newest chats: everything below is already known
                pause();
            }
            if ("NEEDS_LOGIN".equals(error)) unofficial.markNeedsLogin(tenantId, platform, "SESSION_EXPIRED");
        } catch (RuntimeException e) {
            log.warn("Messenger contact import failed: {}", e.getClass().getSimpleName());
            error = "FAILED";
        }
        if (error == null) jdbc.sql("UPDATE chat.contact_imports SET status = 'DONE', fetched = :f, imported = :i, finished_at = now() WHERE id = :id").param("f", fetched).param("i", added).param("id", importId).update();
        else jdbc.sql("UPDATE chat.contact_imports SET status = 'FAILED', error = :e, fetched = :f, imported = :i, finished_at = now() WHERE id = :id").param("e", error).param("f", fetched).param("i", added).param("id", importId).update();
    }

    private void pause() {
        if (sleepMax <= 0) return;
        try { Thread.sleep(sleepMin + (sleepMax > sleepMin ? rnd.nextInt(sleepMax - sleepMin + 1) : 0)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** One contact; true when it was new. Existing rows (an older import or a real chat) are only completed, never overwritten. */
    private boolean upsert(UUID tenantId, OutOfWindowSender.ContactThread t) {
        String ext = t.id() == null ? "" : t.id().trim();
        if (ext.isEmpty() || ext.length() > 120) return false;
        String name = t.name() == null || t.name().isBlank() ? null : (t.name().trim().length() > 40 ? t.name().trim().substring(0, 40) : t.name().trim());
        Instant activity = t.lastActivityMs() > 0 ? Instant.ofEpochMilli(t.lastActivityMs()) : null;
        Instant cap = Instant.now().minus(java.time.Duration.ofHours(25));
        Instant last = activity == null ? null : activity.isAfter(cap) ? cap : activity;
        Timestamp lastTs = last == null ? null : Timestamp.from(last);
        return Boolean.TRUE.equals(jdbc.sql("""
                INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name, created_at, last_customer_at, origin, imported_at, owner_read_at)
                VALUES (:t, 'FACEBOOK', :e, :n, coalesce(CAST(:l AS timestamptz), now()), CAST(:l AS timestamptz), 'DIRECT', now(), now())
                ON CONFLICT (tenant_id, channel, external_id) DO UPDATE SET
                    display_name = coalesce(chat.conversations.display_name, EXCLUDED.display_name),
                    last_customer_at = coalesce(chat.conversations.last_customer_at, EXCLUDED.last_customer_at)
                RETURNING (xmax = 0)
                """).param("t", tenantId).param("e", ext).param("n", name).param("l", lastTs).query(Boolean.class).single());
    }

    /** Once a day: shops with an ACTIVE Messenger session get their newest contacts imported in the background. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 900_000)
    public void scheduled() {
        if (!scheduler) return;
        try { runDaily(); } catch (RuntimeException e) { log.warn("Daily Messenger contact import failed: {}", e.getMessage()); }
    }

    /** Public for tests. Returns how many imports were started. */
    public int runDaily() {
        int started = 0;
        for (UUID t : jdbc.sql("SELECT tenant_id FROM chat.unofficial_sessions WHERE platform = 'FACEBOOK' AND status = 'ACTIVE'").query(UUID.class).list()) {
            boolean fresh = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.contact_imports WHERE tenant_id = :t AND started_at > now() - interval '23 hours')").param("t", t).query(Boolean.class).single();
            if (fresh) continue;
            try { start(t, PLATFORM); started++; } catch (RuntimeException e) { log.debug("Daily Messenger import skipped: {}", e.getMessage()); }
        }
        return started;
    }
}

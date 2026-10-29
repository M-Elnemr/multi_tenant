package com.platform.platform;

import com.platform.notifications.NotificationService;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * A small watchdog for the platform owner: every few minutes it checks the things that fail quietly (stuck webhooks, a growing pile of unsent notifications, a nearly full upload disk,
 * AI replies that keep failing) and sends ONE push per problem per day to the platform owners. It only reads; it never changes shop data.
 */
@Service
public class OpsWatchService {
    private static final Logger log = LoggerFactory.getLogger(OpsWatchService.class);

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final String filesDir;
    private final boolean enabled;

    public OpsWatchService(JdbcClient jdbc, NotificationService notifications, @Value("${app.files.dir:/app/uploads}") String filesDir, @Value("${app.integrations.scheduler.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.filesDir = filesDir;
        this.enabled = enabled;
    }

    /** The problems found right now (empty when everything is fine). Public so a test and an admin screen can read it. */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        long pendingWebhooks = jdbc.sql("SELECT count(*) FROM commerce.webhook_deliveries WHERE status = 'PENDING' AND created_at < now() - interval '30 minutes'").query(Long.class).single();
        if (pendingWebhooks > 200) problems.add("WEBHOOKS_STUCK:" + pendingWebhooks);
        long failedHooks = jdbc.sql("SELECT count(*) FROM commerce.webhook_deliveries WHERE status = 'FAILED' AND created_at > now() - interval '1 hour'").query(Long.class).single();
        if (failedHooks > 100) problems.add("WEBHOOKS_FAILING:" + failedHooks);
        long outbox = jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE status = 'PENDING' AND created_at < now() - interval '1 hour'").query(Long.class).optional().orElse(0L);
        if (outbox > 500) problems.add("NOTIFICATIONS_BACKLOG:" + outbox);
        // the AI seller over the last 30 minutes: failures, switches to the backup model, and slow replies
        var ai = jdbc.sql("""
                SELECT count(*) FILTER (WHERE kind = 'REPLY') AS replies, count(*) FILTER (WHERE kind IN ('ERROR', 'FALLBACK')) AS bad,
                       coalesce(percentile_cont(0.9) WITHIN GROUP (ORDER BY ms) FILTER (WHERE kind = 'REPLY'), 0) AS p90
                FROM chat.ai_events WHERE created_at > now() - interval '30 minutes'
                """).query().singleRow();
        long replies = ((Number) ai.get("replies")).longValue(), bad = ((Number) ai.get("bad")).longValue();
        if (replies >= 10 && bad * 10 > replies) problems.add("AI_FAILING:" + bad + "/" + replies);
        if (replies >= 10 && ((Number) ai.get("p90")).doubleValue() > 25_000) problems.add("AI_SLOW:p90 " + Math.round(((Number) ai.get("p90")).doubleValue() / 1000) + "s");
        // the nightly backup (infra/scripts/backup.sh records each run): none for more than a day once backups have started
        var backup = jdbc.sql("SELECT count(*) AS runs, max(created_at) FILTER (WHERE ok AND kind = 'BACKUP') > now() - interval '26 hours' AS fresh FROM ops.backup_runs").query().singleRow();
        if (((Number) backup.get("runs")).longValue() > 0 && !Boolean.TRUE.equals(backup.get("fresh"))) problems.add("BACKUP_MISSING");
        File dir = new File(filesDir);
        if (dir.exists() && dir.getTotalSpace() > 0) {
            long freePct = dir.getUsableSpace() * 100 / dir.getTotalSpace();
            if (freePct < 10) problems.add("DISK_LOW:" + freePct + "%");
        }
        return problems;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void job() {
        if (!enabled) return;
        try {
            for (String p : check()) alert(p);
        } catch (RuntimeException e) {
            log.warn("Ops watch failed: {}", e.getMessage());
        }
    }

    private void alert(String problem) {
        log.warn("OPS ALERT: {}", problem);
        String kind = problem.contains(":") ? problem.substring(0, problem.indexOf(':')) : problem;
        // one push per problem kind per day
        if (jdbc.sql("INSERT INTO core.ops_alerts (kind, day) VALUES (:k, current_date) ON CONFLICT DO NOTHING").param("k", kind).update() == 0) return;
        for (UUID owner : jdbc.sql("SELECT DISTINCT upr.user_id FROM core.user_platform_roles upr JOIN core.roles r ON r.id = upr.role_id WHERE r.code IN ('PLATFORM_OWNER', 'PLATFORM_ADMIN')").query(UUID.class).list())
            notifications.push(owner, null, "تنبيه تشغيل / Ops alert", problem);
    }
}

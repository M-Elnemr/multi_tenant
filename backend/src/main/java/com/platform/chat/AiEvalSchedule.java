package com.platform.chat;

import com.platform.notifications.NotificationService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The AI seller's quality is measured on a schedule, so a model the provider quietly changed (or a change of ours) shows up before customers notice: every night a core set
 * of the golden conversations runs on each model (GLM and Qwen) against one demo shop, and every Sunday the whole set. A score five points or more below the last run of the
 * same kind sends the platform owners a push. Runs are one at a time, so the models queue. Off with AI_EVAL_SCHEDULE=false.
 */
@Component
public class AiEvalSchedule {
    private static final Logger log = LoggerFactory.getLogger(AiEvalSchedule.class);

    private final AiEvalService eval;
    private final JdbcClient jdbc;
    private final boolean enabled;
    private final String shop;
    private final String models;
    private final Deque<String[]> queue = new ArrayDeque<>();   // {model, kind}

    public AiEvalSchedule(AiEvalService eval, JdbcClient jdbc, NotificationService notifications, @Value("${app.ai.eval.schedule:false}") boolean enabled,
                          @Value("${app.ai.eval.shop:layan-store}") String shop, @Value("${app.ai.eval.models:glm,qwen}") String models) {
        this.eval = eval;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.shop = shop;
        this.models = models;
        eval.onAlert((kind, text) -> {
            if (jdbc.sql("INSERT INTO core.ops_alerts (kind, day) VALUES (:k, current_date) ON CONFLICT DO NOTHING").param("k", kind).update() == 0) return;
            for (UUID owner : jdbc.sql("SELECT DISTINCT upr.user_id FROM core.user_platform_roles upr JOIN core.roles r ON r.id = upr.role_id WHERE r.code IN ('PLATFORM_OWNER', 'PLATFORM_ADMIN')").query(UUID.class).list())
                notifications.push(owner, null, "تنبيه جودة الذكاء الاصطناعي / AI quality alert", text);
        });
    }

    /** The nightly core set: every fourth golden conversation (about 19 of them), the same ones every night so scores compare. */
    static final String CORE = "1,5,9,13,17,21,25,29,33,37,41,45,49,53,57,61,65,69,73";

    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Cairo")
    public void nightly() {
        if (!enabled) return;
        boolean sunday = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
        synchronized (queue) { for (String m : models.split(",")) if (!m.isBlank()) queue.add(new String[]{m.trim(), sunday ? "WEEKLY" : "NIGHTLY"}); }
    }

    /** Starts the next queued run when none is going. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
    public void next() {
        if (!enabled || eval.busy()) return;
        String[] job;
        synchronized (queue) { job = queue.poll(); }
        if (job == null) return;
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop).query(UUID.class).optional().orElse(null);
        if (tenant == null) { log.warn("AI evaluation: shop {} not found", shop); return; }
        try {
            eval.start(tenant, job[0], "WEEKLY".equals(job[1]) ? null : CORE, job[1]);
            log.info("AI evaluation started: {} {} on {}", job[1], job[0], shop);
        } catch (RuntimeException e) {
            log.warn("AI evaluation could not start: {}", e.getMessage());
        }
    }
}

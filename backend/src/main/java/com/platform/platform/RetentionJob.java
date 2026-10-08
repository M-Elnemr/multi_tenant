package com.platform.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps high-churn tables from growing without bound as tenants multiply. Business records (orders, appointments, medical data, invoices) and the
 * audit log are never touched here. Deletes run in small batches so they never hold long locks.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final int BATCH = 5000;

    private final JdbcClient jdbc;

    public RetentionJob(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void run() {
        long total = 0;
        total += purge("notifications.outbox", "created_at", "status = 'SENT' AND created_at < now() - interval '30 days'");
        total += purge("notifications.outbox", "created_at", "status = 'FAILED' AND created_at < now() - interval '90 days'");
        total += purge("notifications.notifications", "created_at", "read_at IS NOT NULL AND created_at < now() - interval '90 days'");
        total += purge("notifications.notifications", "created_at", "created_at < now() - interval '1 year'");
        total += purge("core.idempotency_keys", "created_at", "created_at < now() - interval '7 days'");
        total += purge("billing.webhook_events", "created_at", "created_at < now() - interval '90 days'");
        total += purge("core.user_sessions", "created_at", "(revoked_at IS NOT NULL OR expires_at < now()) AND created_at < now() - interval '30 days'");
        total += purge("core.sso_tickets", "created_at", "created_at < now() - interval '1 day'");
        total += purge("core.activation_pins", "created_at", "(used_at IS NOT NULL OR expires_at < now()) AND created_at < now() - interval '30 days'");
        if (total > 0) log.info("Retention removed {} old rows", total);
    }

    long purge(String table, String orderColumn, String where) {
        long sum = 0;
        int n;
        do {
            n = jdbc.sql("DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + where + " LIMIT " + BATCH + ")").update();
            sum += n;
        } while (n == BATCH);
        return sum;
    }
}

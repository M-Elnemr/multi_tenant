package com.platform.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.shared.Rows;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Background work for connected channels: import products, pull orders, keep stock equal where the owner asked for it. Jobs are claimed with
 * FOR UPDATE SKIP LOCKED (safe with several backend instances), one running job per account, and retried with a growing delay.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PAGES = 60;

    private final JdbcClient jdbc;
    private final IntegrationService integrations;
    private final ListingService listings;
    private final boolean enabled;

    public SyncService(JdbcClient jdbc, IntegrationService integrations, ListingService listings, @Value("${app.integrations.scheduler.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.listings = listings;
        this.enabled = enabled;
    }

    // ---- scheduling ---------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.integrations.job-interval-ms:20000}", initialDelayString = "PT40S")
    public void runDue() {
        if (!enabled) return;
        for (int i = 0; i < 5; i++) if (!runOne()) break;
    }

    /** Orders every 15 minutes, product lists hourly, stock reconciliation every 10 minutes. */
    @Scheduled(cron = "0 */15 * * * *")
    public void enqueueOrders() {
        if (!enabled) return;
        for (var a : jdbc.sql("SELECT id, tenant_id, provider FROM integrations.accounts WHERE status <> 'PAUSED' AND kind IN ('MARKETPLACE', 'STORE_PLATFORM') AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = integrations.accounts.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))").query().listOfRows()) {
            Providers.Def d = Providers.get((String) a.get("provider"));
            if (d != null && Providers.supports(d, "ORDERS")) integrations.enqueue((UUID) a.get("tenant_id"), (UUID) a.get("id"), "PULL_ORDERS", Map.of());
        }
    }

    @Scheduled(cron = "0 7 * * * *")
    public void enqueueProducts() {
        if (!enabled) return;
        for (var a : jdbc.sql("SELECT id, tenant_id, provider FROM integrations.accounts WHERE status <> 'PAUSED' AND kind IN ('MARKETPLACE', 'STORE_PLATFORM') AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = integrations.accounts.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))").query().listOfRows()) {
            Providers.Def d = Providers.get((String) a.get("provider"));
            if (d != null && Providers.supports(d, "PRODUCTS")) integrations.enqueue((UUID) a.get("tenant_id"), (UUID) a.get("id"), "IMPORT_PRODUCTS", Map.of());
        }
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void reconcileStock() {
        if (!enabled) return;
        try { listings.reconcileStock(200); } catch (RuntimeException e) { log.warn("Stock reconcile failed: {}", e.getMessage()); }
    }

    /** A job whose worker died stays RUNNING forever otherwise. */
    @Scheduled(cron = "0 */5 * * * *")
    public void recover() {
        if (!enabled) return;
        jdbc.sql("UPDATE integrations.jobs SET status = 'PENDING', next_run_at = now() WHERE status = 'RUNNING' AND next_run_at < now() - interval '15 minutes'").update();
        jdbc.sql("DELETE FROM integrations.jobs WHERE status IN ('DONE', 'FAILED') AND finished_at < now() - interval '14 days'").update();
    }

    // ---- running ------------------------------------------------------------------------------------------------

    /** Claims and runs one due job; false when none was due. */
    public boolean runOne() {
        var job = jdbc.sql("""
                UPDATE integrations.jobs SET status = 'RUNNING', attempts = attempts + 1, next_run_at = now()
                WHERE id = (SELECT j.id FROM integrations.jobs j WHERE j.status = 'PENDING' AND j.next_run_at <= now()
                              AND NOT EXISTS (SELECT 1 FROM integrations.jobs r WHERE r.account_id = j.account_id AND r.status = 'RUNNING')
                              AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = j.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))
                            ORDER BY j.id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id, tenant_id, account_id, type, payload::text AS payload, attempts
                """).query().listOfRows().stream().findFirst().orElse(null);
        if (job == null) return false;
        long id = ((Number) job.get("id")).longValue();
        UUID accountId = (UUID) job.get("account_id");
        try {
            JsonNode payload = HttpGateway.JSON.readTree((String) job.get("payload"));
            switch ((String) job.get("type")) {
                case "IMPORT_PRODUCTS" -> importProducts(accountId);
                case "PULL_ORDERS" -> pullOrders(accountId, payload.path("full").asBoolean(false));
                default -> throw new IllegalStateException("Unknown job type");
            }
            jdbc.sql("UPDATE integrations.jobs SET status = 'DONE', finished_at = now(), last_error = NULL WHERE id = :i").param("i", id).update();
            integrations.markOk(accountId);
        } catch (Exception e) {
            String msg = IntegrationService.clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            int attempts = ((Number) job.get("attempts")).intValue();
            boolean permanent = e instanceof HttpGateway.ApiException a && (a.status == 401 || a.status == 403) || attempts >= MAX_ATTEMPTS;
            if (permanent) jdbc.sql("UPDATE integrations.jobs SET status = 'FAILED', finished_at = now(), last_error = :m WHERE id = :i").param("i", id).param("m", msg).update();
            else jdbc.sql("UPDATE integrations.jobs SET status = 'PENDING', last_error = :m, next_run_at = now() + CAST(:d AS interval) WHERE id = :i").param("i", id).param("m", msg).param("d", (1L << attempts) + " minutes").update();
            integrations.markError(accountId, msg);
            log.info("Job {} ({}) failed: {}", id, job.get("type"), msg);
        }
        return true;
    }

    // ---- products -----------------------------------------------------------------------------------------------

    /** Reads every product of the account into listings (never overwrites a link the owner made), then links by SKU what matches the shop. */
    public int importProducts(UUID accountId) {
        var account = integrations.rowAny(accountId);
        UUID tenantId = (UUID) account.get("tenant_id");
        ChannelAdapter adapter = integrations.adapter((String) account.get("provider"));
        ChannelAdapter.Ctx ctx = integrations.ctxOf(account);
        String cursor = null;
        int pages = 0, count = 0;
        do {
            Ext.Page<Ext.Product> page = adapter.listProducts(ctx, cursor);
            for (Ext.Product p : page.items()) {
                for (Ext.Variant v : p.variants()) {
                    listings.upsert(tenantId, accountId, p, v, Providers.currencyOf((String) account.get("country")));
                    count++;
                }
            }
            cursor = page.next();
            pages++;
        } while (cursor != null && pages < MAX_PAGES);
        listings.linkBySku(tenantId, accountId);
        return count;
    }

    // ---- orders -------------------------------------------------------------------------------------------------

    public int pullOrders(UUID accountId, boolean full) {
        var account = integrations.rowAny(accountId);
        UUID tenantId = (UUID) account.get("tenant_id");
        ChannelAdapter adapter = integrations.adapter((String) account.get("provider"));
        ChannelAdapter.Ctx ctx = integrations.ctxOf(account);
        var last = jdbc.sql("SELECT max(placed_at) FROM integrations.orders WHERE account_id = :a").param("a", accountId).query(java.sql.Timestamp.class).optional().orElse(null);
        // look back a few days: statuses of recent orders change after they were first seen
        Instant since = full || last == null ? Instant.now().minus(Duration.ofDays(90)) : last.toInstant().minus(Duration.ofDays(5));
        String cursor = null;
        int pages = 0, count = 0;
        do {
            Ext.Page<Ext.Order> page = adapter.listOrders(ctx, since, cursor);
            for (Ext.Order o : page.items()) { upsertOrder(tenantId, accountId, o); count++; }
            cursor = page.next();
            pages++;
        } while (cursor != null && pages < MAX_PAGES);
        return count;
    }

    void upsertOrder(UUID tenantId, UUID accountId, Ext.Order o) {
        try {
            jdbc.sql("""
                    INSERT INTO integrations.orders (tenant_id, account_id, external_id, number, status, stage, total_minor, currency, customer_name, customer_phone, customer_email, address, items, tracking_number, carrier, placed_at)
                    VALUES (:t, :a, :e, :n, :s, :g, :tm, :c, :cn, :cp, :ce, CAST(:ad AS jsonb), CAST(:it AS jsonb), :tr, :ca, :pa)
                    ON CONFLICT (account_id, external_id) DO UPDATE SET number = :n, status = :s, stage = :g, total_minor = :tm, currency = :c,
                      customer_name = coalesce(:cn, integrations.orders.customer_name), customer_phone = coalesce(:cp, integrations.orders.customer_phone), customer_email = coalesce(:ce, integrations.orders.customer_email),
                      address = CAST(:ad AS jsonb), items = CASE WHEN CAST(:it AS jsonb) = '[]'::jsonb THEN integrations.orders.items ELSE CAST(:it AS jsonb) END,
                      tracking_number = coalesce(:tr, integrations.orders.tracking_number), carrier = coalesce(:ca, integrations.orders.carrier), synced_at = now()
                    """).param("t", tenantId).param("a", accountId).param("e", o.id()).param("n", o.number() == null ? o.id() : o.number()).param("s", o.status() == null ? "" : o.status()).param("g", o.stage())
                    .param("tm", o.totalMinor()).param("c", o.currency() == null || o.currency().length() != 3 ? "EGP" : o.currency()).param("cn", blank(o.customerName())).param("cp", blank(o.phone())).param("ce", blank(o.email()))
                    .param("ad", HttpGateway.JSON.writeValueAsString(o.address() == null ? Map.of() : o.address())).param("it", HttpGateway.JSON.writeValueAsString(o.items() == null ? List.of() : o.items()))
                    .param("tr", blank(o.trackingNumber())).param("ca", blank(o.carrier())).param("pa", o.placedAt() == null ? null : java.sql.Timestamp.from(o.placedAt())).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}

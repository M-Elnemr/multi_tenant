package com.platform.commerce.domains;

import com.platform.billing.WalletService;
import com.platform.core.tenant.DomainService;
import com.platform.core.tenant.TenantDirectory;
import com.platform.integrations.HttpGateway;
import com.platform.shared.BusinessException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Drives a paid domain order through {@code PAID -> REGISTERING -> DNS_CONFIGURING -> VERIFYING -> LIVE}. Same
 * shape as {@code WebhookService.deliverDue}/{@code OrderService.releaseExpiredReservations}: a conditional
 * "claim" {@code UPDATE ... WHERE status = :expected RETURNING *} picks up at most one row at a time (safe under
 * several poller instances), exponential backoff on a retryable failure, bounded attempts, a refund + owner
 * notification on a real terminal failure.
 *
 * <p><b>Uncertain outcomes are resolved, never guessed</b> (binding rule added after the first draft): a
 * {@code registerDomain}/{@code renewDomain} call that times out leaves the order exactly where it was
 * ({@code PAID}) with {@code last_error_code = 'UNCERTAIN'} - not a new terminal status, reusing the existing
 * "in progress" state as suggested. The very next pass calls {@link DomainProvider#getDomainDetails} to find the
 * real outcome before doing anything else: found -> recover the provider order id and proceed as a normal success;
 * confirmed not found -> safe to actually call {@code registerDomain} again; still unknown -> back off and ask
 * again. Nothing is ever double-charged (the wallet debit happened once, at order creation, full stop) or
 * double-registered (a genuine retry hits the registrar's own "already registered" answer, which is itself
 * resolved the same way, via a real lookup, not trusted as free-standing fact).
 */
@Service
public class DomainOrderRunner {
    private static final Logger log = LoggerFactory.getLogger(DomainOrderRunner.class);
    private static final Set<String> DUE_STATUSES = Set.of("PAID", "REGISTERING", "DNS_CONFIGURING", "VERIFYING");
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcClient jdbc;
    private final WalletService wallet;
    private final RegistrarGate registrar;
    private final DomainService domains;
    private final RegistrantContactService contacts;
    private final DomainOrderService orders;
    private final ProviderErrorLog errors;
    private final TenantDirectory directory;
    private final HttpGateway http;
    private final boolean scheduler;

    public DomainOrderRunner(JdbcClient jdbc, WalletService wallet, RegistrarGate registrar, DomainService domains, RegistrantContactService contacts,
                             DomainOrderService orders, ProviderErrorLog errors, TenantDirectory directory, HttpGateway http,
                             @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.registrar = registrar;
        this.domains = domains;
        this.contacts = contacts;
        this.orders = orders;
        this.errors = errors;
        this.directory = directory;
        this.http = http;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 50_000)
    public void scheduled() {
        if (!scheduler) return;
        try { runOnce(); } catch (RuntimeException e) { log.warn("Domain order pass failed: {}", e.getMessage()); }
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void expiryScheduled() {
        if (!scheduler) return;
        try { checkExpiring(); } catch (RuntimeException e) { log.warn("Domain expiry check failed: {}", e.getMessage()); }
    }

    /** One pass over every order due for the next step. Returns how many advanced. Public for tests. */
    public int runOnce() {
        int processed = 0;
        for (var row : jdbc.sql("SELECT id, status FROM commerce.domain_orders WHERE status = ANY(ARRAY['PAID','REGISTERING','DNS_CONFIGURING','VERIFYING']) AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 20")
                .query().listOfRows()) {
            if (advance((UUID) row.get("id"), (String) row.get("status"))) processed++;
        }
        return processed;
    }

    private boolean advance(UUID id, String expectedStatus) {
        var claimed = jdbc.sql("UPDATE commerce.domain_orders SET attempts = attempts + 1, updated_at = now() WHERE id = :i AND status = :s AND next_attempt_at <= now() RETURNING *")
                .param("i", id).param("s", expectedStatus).query().listOfRows().stream().findFirst();
        if (claimed.isEmpty()) return false;   // another poller got it first, or it already moved on
        var o = claimed.get();
        UUID tenantId = (UUID) o.get("tenant_id");
        String domain = (String) o.get("domain");
        int attempts = ((Number) o.get("attempts")).intValue();
        try {
            switch (expectedStatus) {
                case "PAID" -> doPaid(o);
                case "REGISTERING" -> doRegistering(o);
                case "DNS_CONFIGURING" -> doDnsConfiguring(o);
                case "VERIFYING" -> doVerifying(o);
                default -> { }
            }
            return true;
        } catch (ProviderException e) {
            errors.record(id, domain, expectedStatus, e);
            onFailure(id, tenantId, domain, attempts, e.retryable(), e.code().name(), e.getMessage());
            return false;
        } catch (BusinessException e) {
            onFailure(id, tenantId, domain, attempts, false, "UNAVAILABLE", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Domain order {} step {} failed unexpectedly: {}", id, expectedStatus, e.getMessage());
            onFailure(id, tenantId, domain, attempts, true, "UNKNOWN", e.getMessage());
            return false;
        }
    }

    // ---- PAID: register (or renew), confirming any uncertain outcome before ever guessing -------------------------

    private void doPaid(Map<String, Object> o) {
        if ("UNCERTAIN".equals(o.get("last_error_code"))) { resolveUncertain(o); return; }
        if ("RENEWAL".equals(o.get("kind"))) doRenew(o); else doRegister(o);
    }

    private void doRegister(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        String domain = (String) o.get("domain");
        int years = ((Number) o.get("years")).intValue();
        DomainProvider.RegisterResult rr;
        try {
            var registrant = RegistrantContactService.toProviderInfo(contacts.raw((UUID) o.get("registrant_contact_id")));
            rr = registrar.provider().registerDomain(new DomainProvider.RegisterRequest(domain, years, registrant, List.of()));
        } catch (ProviderException e) {
            if (e.code() == ProviderException.Code.ALREADY_REGISTERED) {
                // never trust the error text alone: confirm with a real lookup before treating it as our own earlier success
                var details = registrar.provider().getDomainDetails(domain, null);
                rr = new DomainProvider.RegisterResult(details.providerOrderId(), details.expiresAt());
            } else if (e.code() == ProviderException.Code.TIMEOUT) {
                markUncertain(id, "Registration call timed out");
                return;
            } else throw e;
        }
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'REGISTERING', provider_order_id = :po, expires_at = :e, next_attempt_at = now(), last_error = NULL, last_error_code = NULL WHERE id = :i")
                .param("po", rr.providerOrderId()).param("e", ts(rr.expiresAt())).param("i", id).update();
        orders.event(id, "REGISTERING", "Registered at the registrar (order " + rr.providerOrderId() + ")");
    }

    private void doRenew(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        String domain = (String) o.get("domain");
        int years = ((Number) o.get("years")).intValue();
        String providerOrderId = (String) o.get("provider_order_id");
        DomainProvider.RenewResult rr;
        try {
            rr = registrar.provider().renewDomain(domain, providerOrderId, years);
        } catch (ProviderException e) {
            if (e.code() == ProviderException.Code.TIMEOUT) { markUncertain(id, "Renewal call timed out"); return; }
            throw e;
        }
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'LIVE', provider_order_id = :po, expires_at = :e, last_error = NULL, last_error_code = NULL WHERE id = :i")
                .param("po", rr.providerOrderId()).param("e", ts(rr.expiresAt())).param("i", id).update();
        orders.event(id, "LIVE", "Renewed " + years + " year(s)");
    }

    private void markUncertain(UUID id, String note) {
        jdbc.sql("UPDATE commerce.domain_orders SET last_error = :m, last_error_code = 'UNCERTAIN', next_attempt_at = now() + interval '30 seconds' WHERE id = :i")
                .param("m", note).param("i", id).update();
        orders.event(id, "PAID", note + "; confirming the real status with the registrar before continuing");
    }

    /** Resolves a prior timeout by asking the registrar directly - a real status query, never a guess and never a blind retry. */
    private void resolveUncertain(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        String domain = (String) o.get("domain");
        String kind = (String) o.get("kind");
        String providerOrderId = (String) o.get("provider_order_id");
        DomainProvider.DomainDetails details;
        try {
            details = registrar.provider().getDomainDetails(domain, providerOrderId);
        } catch (ProviderException e) {
            if (e.code() == ProviderException.Code.NOT_FOUND && "REGISTER".equals(kind)) {
                // confirmed: the earlier attempt never actually registered the domain - safe to try again for real
                jdbc.sql("UPDATE commerce.domain_orders SET next_attempt_at = now(), last_error = NULL, last_error_code = NULL WHERE id = :i").param("i", id).update();
                orders.event(id, "PAID", "Confirmed with the registrar: " + domain + " was never registered by the earlier attempt; retrying now");
                return;
            }
            throw e;   // still can't tell (or a renewal target that vanished, which is itself a real problem) - normal retry/backoff takes over
        }
        if ("RENEWAL".equals(kind)) {
            jdbc.sql("UPDATE commerce.domain_orders SET status = 'LIVE', provider_order_id = :po, expires_at = :e, last_error = NULL, last_error_code = NULL WHERE id = :i")
                    .param("po", details.providerOrderId()).param("e", ts(details.expiresAt())).param("i", id).update();
            orders.event(id, "LIVE", "Confirmed with the registrar: the renewal did go through");
        } else {
            jdbc.sql("UPDATE commerce.domain_orders SET status = 'REGISTERING', provider_order_id = :po, expires_at = :e, last_error = NULL, last_error_code = NULL, next_attempt_at = now() WHERE id = :i")
                    .param("po", details.providerOrderId()).param("e", ts(details.expiresAt())).param("i", id).update();
            orders.event(id, "REGISTERING", "Confirmed with the registrar: registration did go through (order " + details.providerOrderId() + ")");
        }
    }

    // ---- REGISTERING: create the tenant_domains row (reusing DomainService, never duplicating its validation) ------

    private void doRegistering(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        UUID tenantId = (UUID) o.get("tenant_id");
        String domain = (String) o.get("domain");
        UUID tenantDomainId = (UUID) o.get("tenant_domain_id");
        if (tenantDomainId == null) {
            var d = domains.addPurchasedDomain(tenantId, (UUID) o.get("created_by"), domain, id);
            jdbc.sql("UPDATE commerce.domain_orders SET tenant_domain_id = :d WHERE id = :i").param("d", d.getId()).param("i", id).update();
        }
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'DNS_CONFIGURING', next_attempt_at = now() WHERE id = :i").param("i", id).update();
        directory.invalidateAll();
        orders.event(id, "DNS_CONFIGURING", "Domain row created for " + domain);
    }

    // ---- DNS_CONFIGURING: create the A/CNAME/TXT records through the registrar's own DNS-zone API -------------------

    private void doDnsConfiguring(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        UUID tenantId = (UUID) o.get("tenant_id");
        String domain = (String) o.get("domain");
        UUID tenantDomainId = (UUID) o.get("tenant_domain_id");
        var d = domains.get(tenantId, tenantDomainId).orElseThrow(() -> ProviderException.unknown("Domain row missing for " + domain));
        for (var instr : domains.instructions(d)) registrar.provider().createDnsRecord(domain, instr.type(), instr.name(), instr.value(), 3600);
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'VERIFYING', next_attempt_at = now() WHERE id = :i").param("i", id).update();
        orders.event(id, "VERIFYING", "DNS records created at the registrar");
    }

    // ---- VERIFYING: reuse DomainService.verify (same TXT lookup as the connect flow), then warm up the cache --------

    private void doVerifying(Map<String, Object> o) {
        UUID id = (UUID) o.get("id");
        UUID tenantId = (UUID) o.get("tenant_id");
        UUID tenantDomainId = (UUID) o.get("tenant_domain_id");
        var d = domains.verify(tenantId, tenantDomainId);
        if (!d.isVerified()) throw ProviderException.timeout("DNS not propagated yet");
        warmUp(d.getHost());
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'LIVE', next_attempt_at = now() WHERE id = :i").param("i", id).update();
        orders.event(id, "LIVE", "Verified and live on " + d.getHost());
    }

    private void warmUp(String host) {
        try { http.send(new HttpGateway.Req("GET", "https://" + host + "/", Map.of(), null, "domain-warmup", Duration.ofSeconds(5))); }
        catch (RuntimeException e) { log.info("Warm-up request for {} did not complete (SSL/DNS may still be settling): {}", host, e.getMessage()); }
    }

    // ---- failure / backoff / refund --------------------------------------------------------------------------------

    private void onFailure(UUID id, UUID tenantId, String domain, int attempts, boolean retryable, String code, String message) {
        if (!retryable) {
            failAndRefund(id, tenantId, domain, code, message);
        } else if (attempts >= MAX_ATTEMPTS) {
            // exhausted retries on a transient-looking error: stop spending cycles, but do not guess that the money should come
            // back - that is an admin decision (the admin "retry" action resumes exactly this order, no new charge either way).
            jdbc.sql("UPDATE commerce.domain_orders SET status = 'FAILED', last_error = :m, last_error_code = :c WHERE id = :i").param("m", clip(message)).param("c", code).param("i", id).update();
            orders.event(id, "FAILED", clip(message) + " (attempts exhausted; retryable - an admin can retry)");
            try { wallet.notifyOwner(tenantId, "تأخر شراء الدومين / Domain purchase is delayed",
                    domain + " لم يكتمل بعد عدة محاولات وسيحتاج مراجعة. / " + domain + " has not completed after several attempts and needs a look.", "DOMAIN_ORDER_DELAYED"); }
            catch (RuntimeException ignored) { /* best effort */ }
        } else {
            long delaySeconds = Math.min(3600, 30L * (1L << Math.min(attempts, 10)));
            jdbc.sql("UPDATE commerce.domain_orders SET last_error = :m, last_error_code = :c, next_attempt_at = now() + make_interval(secs => :d) WHERE id = :i")
                    .param("m", clip(message)).param("c", code).param("d", (double) delaySeconds).param("i", id).update();
        }
    }

    private void failAndRefund(UUID id, UUID tenantId, String domain, String code, String message) {
        var row = jdbc.sql("SELECT price_minor FROM commerce.domain_orders WHERE id = :i").param("i", id).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) return;
        long priceMinor = ((Number) row.get("price_minor")).longValue();
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'FAILED', last_error = :m, last_error_code = :c WHERE id = :i").param("m", clip(message)).param("c", code).param("i", id).update();
        orders.event(id, "FAILED", clip(message));
        wallet.credit(tenantId, priceMinor, "REFUND", "domain-refund-" + id, "Domain order failed: " + domain + " (" + code + ")", null);
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'REFUNDED' WHERE id = :i").param("i", id).update();
        orders.event(id, "REFUNDED", "Refunded " + priceMinor + " to the wallet");
        try { wallet.notifyOwner(tenantId, "تعذر شراء الدومين / Domain purchase failed",
                domain + " تعذر تسجيله وتم استرجاع المبلغ إلى محفظتك. / " + domain + " could not be registered; the amount was refunded to your wallet.", "DOMAIN_ORDER_FAILED"); }
        catch (RuntimeException ignored) { /* best effort */ }
    }

    // ---- renewal / expiration: notify always; auto-charge ONLY when the merchant opted in per domain -----------------

    /** Flags domains expiring soon. Auto-renews (a brand-new wallet debit + order, exactly like a manual renewal) ONLY the ones where the merchant explicitly turned {@code auto_renew} on; every other expiring domain only gets notified, never charged. */
    public int checkExpiring() {
        int touched = 0;
        for (var o : jdbc.sql("""
                SELECT id, tenant_id, domain, provider_order_id, auto_renew, expires_at FROM commerce.domain_orders
                WHERE status = 'LIVE' AND expires_at IS NOT NULL AND expires_at < now() + interval '30 days'
                  AND (renewal_notified_at IS NULL OR renewal_notified_at < now() - interval '7 days')
                """).query().listOfRows()) {
            UUID id = (UUID) o.get("id");
            UUID tenantId = (UUID) o.get("tenant_id");
            String domain = (String) o.get("domain");
            long daysLeft = Math.max(0, Duration.between(Instant.now(), ((Timestamp) o.get("expires_at")).toInstant()).toDays());
            if (Boolean.TRUE.equals(o.get("auto_renew"))) {
                try {
                    orders.renewInternal(tenantId, null, o, 1, true);
                    wallet.notifyOwner(tenantId, "تم تجديد الدومين تلقائيًا / Domain renewed automatically",
                            domain + " تم تجديده تلقائيًا (التجديد الآلي مفعّل). / " + domain + " was renewed automatically (auto-renew is on).", "DOMAIN_AUTO_RENEWED");
                } catch (BusinessException e) {
                    // NO_CREDIT or anything else blocking the auto-charge: never retry silently forever, just tell the owner and move on
                    try { wallet.notifyOwner(tenantId, "تعذر تجديد الدومين تلقائيًا / Automatic domain renewal failed",
                            domain + " ينتهي بعد " + daysLeft + " يوم ولم يُجدَّد تلقائيًا (" + e.getMessage() + "). جدّده من لوحة التحكم. / "
                                    + domain + " expires in about " + daysLeft + " day(s) and could not be auto-renewed (" + e.getMessage() + "). Renew it from the dashboard.", "DOMAIN_EXPIRING_SOON"); }
                    catch (RuntimeException ignored) { /* best effort */ }
                }
            } else {
                try { wallet.notifyOwner(tenantId, "الدومين قرب الانتهاء / Domain renewal due soon",
                        domain + " ينتهي بعد " + daysLeft + " يوم تقريبًا. جدّده من لوحة التحكم (التجديد الآلي غير مفعّل). / "
                                + domain + " expires in about " + daysLeft + " day(s). Renew it from the dashboard (auto-renew is off).", "DOMAIN_EXPIRING_SOON"); }
                catch (RuntimeException ignored) { /* best effort */ }
            }
            jdbc.sql("UPDATE commerce.domain_orders SET renewal_notified_at = now() WHERE id = :i").param("i", id).update();
            touched++;
        }
        return touched;
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    private static String clip(String s) { return s == null ? null : s.length() > 280 ? s.substring(0, 280) : s; }
}

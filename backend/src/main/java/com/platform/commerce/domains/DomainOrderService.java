package com.platform.commerce.domains;

import com.platform.audit.AuditService;
import com.platform.billing.WalletService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merchant-facing side of a domain purchase: {@code domain_quotes -> domain_orders}, wallet-funded, re-checked
 * live against the registrar at the moment of purchase (a quote is only a price snapshot, never trusted blindly).
 * The actual registration/DNS/verification work happens asynchronously in {@link DomainOrderRunner}; this class
 * only ever gets an order to {@code PAID} (or drives the merchant-visible renewal/registrant/transfer actions).
 */
@Service
public class DomainOrderService {
    private final JdbcClient jdbc;
    private final WalletService wallet;
    private final RegistrarGate registrar;
    private final RegistrantContactService contacts;
    private final DomainPricingService pricing;
    private final AuditService audit;
    private final ProviderErrorLog errors;

    public DomainOrderService(JdbcClient jdbc, WalletService wallet, RegistrarGate registrar, RegistrantContactService contacts,
                              DomainPricingService pricing, AuditService audit, ProviderErrorLog errors) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.registrar = registrar;
        this.contacts = contacts;
        this.pricing = pricing;
        this.audit = audit;
        this.errors = errors;
    }

    public record CreateReq(UUID quoteId, Integer years, UUID registrantContactId) {}

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actor, CreateReq r) {
        DomainProvider provider = registrar.require();   // 409 REGISTRAR_NOT_CONFIGURED before anything else is even looked at
        if (r == null || r.quoteId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "quoteId is required");
        if (r.registrantContactId() == null) throw BusinessException.badRequest("VALIDATION_ERROR", "registrantContactId is required");
        var quote = jdbc.sql("SELECT domain, tld FROM commerce.domain_quotes WHERE id = :i AND tenant_id = :t AND expires_at > now()")
                .param("i", r.quoteId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.conflict("QUOTE_EXPIRED", "This price quote has expired; search again"));
        String domain = (String) quote.get("domain"), tld = (String) quote.get("tld");
        int years = r.years() == null ? 1 : r.years();
        if (years < 1 || years > 10) throw BusinessException.badRequest("VALIDATION_ERROR", "years must be between 1 and 10");
        var contact = contacts.get(tenantId, r.registrantContactId());   // 404 unless it is this tenant's own, real contact - never fabricated

        if (jdbc.sql("SELECT count(*) FROM commerce.domain_orders WHERE tenant_id = :t AND domain = :d AND status IN ('PENDING_PAYMENT','PAID','REGISTERING','DNS_CONFIGURING','VERIFYING')")
                .param("t", tenantId).param("d", domain).query(Long.class).single() > 0)
            throw BusinessException.conflict("ORDER_IN_PROGRESS", "A purchase for this domain is already in progress");

        // re-validate against a FRESH provider call: the quote/price snapshot is a UI convenience only
        List<DomainProvider.AvailabilityResult> avail;
        try { avail = provider.checkAvailability(List.of(domain)); }
        catch (ProviderException e) { errors.record(null, domain, "availability", e); throw QuoteService.toBusiness(e); }
        if (avail.isEmpty() || !avail.get(0).available()) throw BusinessException.conflict("DOMAIN_UNAVAILABLE", "This domain is no longer available");

        var rule = pricing.rule(tld).orElseThrow(() -> BusinessException.badRequest("VALIDATION_ERROR", "This TLD is not offered"));
        DomainProvider.TldPrice price;
        try { price = provider.getPricing(List.of(tld)).stream().findFirst().orElseThrow(() -> ProviderException.unknown("No price for " + tld)); }
        catch (ProviderException e) { errors.record(null, domain, "pricing", e); throw QuoteService.toBusiness(e); }
        long priceMinor = DomainPricingService.applyMarkup(price.registerProviderPriceMinor(), rule);

        UUID orderId = UUID.randomUUID();
        String ref = "domain-order-" + orderId;
        if (!wallet.tryDebit(tenantId, priceMinor, "DOMAIN", ref, "Domain purchase: " + domain))
            throw BusinessException.conflict("NO_CREDIT", "Not enough wallet balance for this domain");

        try {
            jdbc.sql("""
                    INSERT INTO commerce.domain_orders (id, tenant_id, domain, kind, years, price_minor, provider_price_minor, currency, status, wallet_ref, registrant_contact_id, created_by)
                    VALUES (:id, :t, :d, 'REGISTER', :y, :p, :pp, :c, 'PAID', :ref, :rc, :by)
                    """).param("id", orderId).param("t", tenantId).param("d", domain).param("y", years).param("p", priceMinor).param("pp", price.registerProviderPriceMinor())
                    .param("c", rule.get("currency")).param("ref", ref).param("rc", contact.get("id")).param("by", actor).update();
        } catch (DataIntegrityViolationException e) {
            // the partial unique index caught a race the pre-check missed: give the money straight back, never leave it debited with no order
            wallet.credit(tenantId, priceMinor, "REFUND", ref, "Refunded: a purchase for this domain was already in progress", null);
            throw BusinessException.conflict("ORDER_IN_PROGRESS", "A purchase for this domain is already in progress");
        }
        event(orderId, "PAID", "Payment taken from the wallet");
        audit.record(actor, tenantId, "DOMAIN_ORDER_CREATED", "domain_order", orderId, "{\"domain\":\"" + domain + "\"}");
        return created(orderId, domain, priceMinor);
    }

    private static Map<String, Object> created(UUID id, String domain, long priceMinor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("domain", domain);
        out.put("status", "PAID");
        out.put("priceMinor", priceMinor);
        out.put("createdAt", Instant.now().toString());
        return out;
    }

    public Map<String, Object> get(UUID tenantId, UUID orderId) {
        var o = jdbc.sql("""
                SELECT id, domain, kind, years, price_minor, currency, status, provider_order_id, registrant_contact_id, tenant_domain_id,
                       last_error, last_error_code, expires_at, auto_renew, created_at, updated_at
                FROM commerce.domain_orders WHERE id = :i AND tenant_id = :t
                """).param("i", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        Map<String, Object> out = Rows.camel(o);
        out.put("host", o.get("domain"));
        out.put("events", Rows.camel(jdbc.sql("SELECT id, created_at AS at, status, note FROM commerce.domain_order_events WHERE order_id = :i ORDER BY id").param("i", orderId).query().listOfRows()));
        return out;
    }

    public List<Map<String, Object>> list(UUID tenantId) {
        return Rows.camel(jdbc.sql("""
                SELECT id, domain, domain AS host, status, price_minor, years, tenant_domain_id, auto_renew, created_at
                FROM commerce.domain_orders WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 200
                """).param("t", tenantId).query().listOfRows());
    }

    public record RenewReq(Integer years) {}

    /** Explicit, merchant-triggered renewal - always allowed regardless of the auto-renew flag, which only gates the UNATTENDED path. */
    @Transactional
    public Map<String, Object> renew(UUID tenantId, UUID actor, UUID orderId, RenewReq r) {
        var existing = lockLive(tenantId, orderId);
        int years = r == null || r.years() == null ? 1 : r.years();
        return renewInternal(tenantId, actor, existing, years, false);
    }

    /**
     * Shared by the explicit renew action above and {@code DomainOrderRunner}'s auto-renew pass. Always a brand-new
     * wallet debit + order row, exactly like a fresh purchase - the only difference the caller makes is whether this
     * may run unattended, which {@code DomainOrderRunner} only ever does when {@code auto_renew} was explicitly
     * turned on by the merchant beforehand (never on by default, never inferred).
     */
    Map<String, Object> renewInternal(UUID tenantId, UUID actor, Map<String, Object> existing, int years, boolean automatic) {
        DomainProvider provider = registrar.require();
        String domain = (String) existing.get("domain");
        if (years < 1 || years > 10) throw BusinessException.badRequest("VALIDATION_ERROR", "years must be between 1 and 10");
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        var rule = pricing.rule(tld).orElseThrow(() -> BusinessException.badRequest("VALIDATION_ERROR", "This TLD is not offered"));
        DomainProvider.TldPrice price;
        try { price = provider.getPricing(List.of(tld)).stream().findFirst().orElseThrow(() -> ProviderException.unknown("No price for " + tld)); }
        catch (ProviderException e) { errors.record((UUID) existing.get("id"), domain, "pricing", e); throw QuoteService.toBusiness(e); }
        long priceMinor = DomainPricingService.applyMarkup(price.renewProviderPriceMinor(), rule);

        UUID newOrderId = UUID.randomUUID();
        String ref = "domain-renew-" + newOrderId;
        String note = automatic ? "Auto-renewal (opted in): " + domain : "Domain renewal: " + domain;
        if (!wallet.tryDebit(tenantId, priceMinor, "DOMAIN", ref, note))
            throw BusinessException.conflict("NO_CREDIT", "Not enough wallet balance to renew this domain");
        jdbc.sql("""
                INSERT INTO commerce.domain_orders (id, tenant_id, domain, kind, years, price_minor, provider_price_minor, currency, status, wallet_ref, provider_order_id, auto_renew, created_by)
                VALUES (:id, :t, :d, 'RENEWAL', :y, :p, :pp, :c, 'PAID', :ref, :po, :ar, :by)
                """).param("id", newOrderId).param("t", tenantId).param("d", domain).param("y", years).param("p", priceMinor).param("pp", price.renewProviderPriceMinor())
                .param("c", rule.get("currency")).param("ref", ref).param("po", existing.get("provider_order_id")).param("ar", Boolean.TRUE.equals(existing.get("auto_renew"))).param("by", actor).update();
        event(newOrderId, "PAID", note);
        audit.record(actor, tenantId, automatic ? "DOMAIN_AUTO_RENEWED" : "DOMAIN_RENEWAL_ORDERED", "domain_order", newOrderId, "{\"domain\":\"" + domain + "\"}");
        return created(newOrderId, domain, priceMinor);
    }

    public record AutoRenewReq(Boolean enabled) {}

    /** The explicit, separately-given consent the renewal job checks before it is ever allowed to touch the wallet unattended. Off by default. */
    @Transactional
    public Map<String, Object> setAutoRenew(UUID tenantId, UUID actor, UUID orderId, AutoRenewReq r) {
        var o = lockLive(tenantId, orderId);
        boolean enabled = r != null && Boolean.TRUE.equals(r.enabled());
        jdbc.sql("UPDATE commerce.domain_orders SET auto_renew = :e WHERE id = :i").param("e", enabled).param("i", orderId).update();
        event(orderId, (String) o.get("status"), enabled ? "Auto-renew turned on by the merchant" : "Auto-renew turned off by the merchant");
        audit.record(actor, tenantId, enabled ? "DOMAIN_AUTO_RENEW_ENABLED" : "DOMAIN_AUTO_RENEW_DISABLED", "domain_order", orderId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", orderId);
        out.put("autoRenew", enabled);
        return out;
    }

    /** Ownership clarity: the legal registrant behind this order, shown plainly - never Elmanassa's own details. */
    public Map<String, Object> registrant(UUID tenantId, UUID orderId) {
        var o = jdbc.sql("SELECT registrant_contact_id FROM commerce.domain_orders WHERE id = :i AND tenant_id = :t")
                .param("i", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        UUID contactId = (UUID) o.get("registrant_contact_id");
        if (contactId == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "No registrant contact on this order");
        return contacts.get(tenantId, contactId);
    }

    /**
     * Ownership protection (required this round, not deferred): unlocks the domain at the registrar and hands the
     * merchant a fresh EPP/auth code to transfer out to another registrar, or simply to recover their own domain if
     * something goes wrong with the platform. Shown once, like a webhook secret - never persisted in the clear.
     */
    @Transactional
    public Map<String, Object> transferAuthCode(UUID tenantId, UUID actor, UUID orderId) {
        DomainProvider provider = registrar.require();
        var o = jdbc.sql("SELECT id, domain, status, provider_order_id FROM commerce.domain_orders WHERE id = :i AND tenant_id = :t")
                .param("i", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        if (!"LIVE".equals(o.get("status"))) throw BusinessException.badRequest("VALIDATION_ERROR", "Only a live, registered domain can be transferred out");
        String domain = (String) o.get("domain");
        String providerOrderId = (String) o.get("provider_order_id");
        try {
            provider.setTransferLock(domain, providerOrderId, false);
            String code = provider.resetAuthCode(domain, providerOrderId);
            event(orderId, "LIVE", "Unlocked for transfer and issued a new EPP/auth code");
            audit.record(actor, tenantId, "DOMAIN_TRANSFER_AUTH_CODE_ISSUED", "domain_order", orderId, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("domain", domain);
            out.put("authCode", code);
            out.put("issuedAt", Instant.now().toString());
            out.put("note", "Copy this code now; it is not shown again. Share it only with the registrar you are transferring to.");
            return out;
        } catch (ProviderException e) {
            errors.record(orderId, domain, "transfer-auth-code", e);
            throw QuoteService.toBusiness(e);
        }
    }

    void event(UUID orderId, String status, String note) {
        jdbc.sql("INSERT INTO commerce.domain_order_events (order_id, status, note) VALUES (:o, :s, :n)").param("o", orderId).param("s", status).param("n", note).update();
    }

    Map<String, Object> lockLive(UUID tenantId, UUID orderId) {
        var o = jdbc.sql("SELECT id, tenant_id, domain, status, provider_order_id, auto_renew FROM commerce.domain_orders WHERE id = :i AND tenant_id = :t FOR UPDATE")
                .param("i", orderId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        if (!"LIVE".equals(o.get("status"))) throw BusinessException.badRequest("VALIDATION_ERROR", "This action only applies to a live, registered domain");
        return o;
    }
}

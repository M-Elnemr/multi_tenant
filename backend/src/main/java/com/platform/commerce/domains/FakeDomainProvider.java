package com.platform.commerce.domains;

import com.platform.core.tenant.DnsVerifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link DomainProvider}. NOT a Spring bean (no {@code @Component}): tests that need it wire one
 * instance per test class as a {@code @Primary} bean, the same way {@code DomainCheckIntegrationTest} wires a fake
 * {@link DnsVerifier} - and in fact this class also implements {@link DnsVerifier}, backed by the very same
 * in-memory DNS records {@link #createDnsRecord} stores, so a test that wires this as both beans sees the whole
 * purchase pipeline (register -> create DNS -> verify) work end to end without any real network or DNS lookup.
 *
 * Every method is fully controllable: {@link #setAvailability}, {@link #setPricing}, {@link #forceError} (any
 * operation, any domain, any {@link ProviderException}), {@link #timeoutThenSucceedRegister} (the provider's own
 * order actually goes through despite the client-visible timeout - the exact "uncertain outcome" case the runner
 * must survive without double-charging or double-registering).
 */
public class FakeDomainProvider implements DomainProvider, DnsVerifier {
    private final Map<String, AvailabilityResult> availability = new ConcurrentHashMap<>();
    private final Map<String, TldPrice> pricing = new ConcurrentHashMap<>();
    private final Map<String, ProviderException> forcedErrors = new ConcurrentHashMap<>();
    private final Map<String, ProviderException> stickyErrors = new ConcurrentHashMap<>();
    private final Set<String> timeoutOnceRegister = ConcurrentHashMap.newKeySet();
    private final Map<String, String> registeredOrderId = new ConcurrentHashMap<>();
    private final Map<String, Instant> registeredExpiry = new ConcurrentHashMap<>();
    private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
    private final Map<String, List<DnsRecord>> dnsByName = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger();
    private volatile boolean dnsCreateFails = false;
    private final Map<String, Boolean> transferLocked = new ConcurrentHashMap<>();
    private final Map<String, String> authCodes = new ConcurrentHashMap<>();

    // ---- test controls -------------------------------------------------------------------------------------

    public void setAvailability(String domain, boolean available, boolean premium, long providerPriceMinor, String currency) {
        availability.put(domain, new AvailabilityResult(domain, available, premium, providerPriceMinor, currency));
    }

    public void setPricing(String tld, long registerProviderPriceMinor, long renewProviderPriceMinor, String currency) {
        pricing.put(tld, new TldPrice(tld, registerProviderPriceMinor, renewProviderPriceMinor, currency));
    }

    /** The next call to {@code operation} ("register", "renew", "availability", "pricing", "dns") for this domain/tld throws {@code ex} once. */
    public void forceError(String operation, String key, ProviderException ex) { forcedErrors.put(operation + ":" + key, ex); }

    /** Every call to {@code operation} for this domain/tld throws {@code ex} until {@link #clearStickyError} - for testing bounded-attempts exhaustion. */
    public void forceErrorAlways(String operation, String key, ProviderException ex) { stickyErrors.put(operation + ":" + key, ex); }

    public void clearStickyError(String operation, String key) { stickyErrors.remove(operation + ":" + key); }

    /** The registrar's own order actually completes, but the caller sees a client-side timeout on this attempt only; a retry must see ALREADY_REGISTERED, not a second order. */
    public void timeoutThenSucceedRegister(String domain) { timeoutOnceRegister.add(domain); }

    public void failDnsCreate(boolean fail) { dnsCreateFails = fail; }

    public int callCount(String operation, String key) { return callCounts.getOrDefault(operation + ":" + key, 0); }

    public String providerOrderIdOf(String domain) { return registeredOrderId.get(domain); }

    // ---- DomainProvider -------------------------------------------------------------------------------------

    @Override public String code() { return "fake"; }

    @Override
    public List<AvailabilityResult> checkAvailability(List<String> domains) {
        List<AvailabilityResult> out = new ArrayList<>();
        for (String d : domains) {
            count("availability", d);
            ProviderException forced = forcedErrors.remove("availability:" + d);
            if (forced != null) throw forced;
            out.add(availability.getOrDefault(d, new AvailabilityResult(d, true, false, 100_000, "EGP")));
        }
        return out;
    }

    @Override
    public List<TldPrice> getPricing(List<String> tlds) {
        List<TldPrice> out = new ArrayList<>();
        for (String t : tlds) {
            count("pricing", t);
            ProviderException forced = forcedErrors.remove("pricing:" + t);
            if (forced != null) throw forced;
            out.add(pricing.getOrDefault(t, new TldPrice(t, 100_000, 100_000, "EGP")));
        }
        return out;
    }

    @Override
    public RegisterResult registerDomain(RegisterRequest req) {
        String domain = req.domain();
        count("register", domain);
        ProviderException forced = forcedErrors.remove("register:" + domain);
        if (forced != null) throw forced;
        ProviderException sticky = stickyErrors.get("register:" + domain);
        if (sticky != null) throw sticky;
        if (req.registrant() == null || req.registrant().email() == null || req.registrant().email().isBlank())
            throw ProviderException.invalidContact("Registrant contact is required");
        if (registeredOrderId.containsKey(domain)) throw ProviderException.alreadyRegistered(domain);   // a retry after our own earlier success
        String orderId = "FAKE-" + idSeq.incrementAndGet();
        Instant expires = Instant.now().plus(365L * Math.max(1, req.years()), ChronoUnit.DAYS);
        registeredOrderId.put(domain, orderId);
        registeredExpiry.put(domain, expires);
        if (timeoutOnceRegister.remove(domain)) throw ProviderException.timeout("Simulated timeout; the registrar order actually went through");
        return new RegisterResult(orderId, expires);
    }

    @Override
    public RenewResult renewDomain(String domain, String providerOrderId, int years) {
        count("renew", domain);
        ProviderException forced = forcedErrors.remove("renew:" + domain);
        if (forced != null) throw forced;
        Instant base = registeredExpiry.getOrDefault(domain, Instant.now());
        Instant expires = base.plus(365L * Math.max(1, years), ChronoUnit.DAYS);
        registeredExpiry.put(domain, expires);
        return new RenewResult(providerOrderId != null ? providerOrderId : registeredOrderId.getOrDefault(domain, "FAKE-" + idSeq.incrementAndGet()), expires);
    }

    @Override
    public TransferResult transferDomain(TransferRequest req) {
        count("transfer", req.domain());
        ProviderException forced = forcedErrors.remove("transfer:" + req.domain());
        if (forced != null) throw forced;
        return new TransferResult("FAKE-" + idSeq.incrementAndGet(), "PENDING");
    }

    @Override
    public DomainDetails getDomainDetails(String domain, String providerOrderId) {
        String id = registeredOrderId.get(domain);
        if (id == null) throw ProviderException.notFound("No such order for " + domain);
        return new DomainDetails(domain, id, "ACTIVE", registeredExpiry.get(domain), List.of(), false);
    }

    @Override
    public void updateNameservers(String domain, String providerOrderId, List<String> nameservers) { /* no-op: nothing downstream reads this in tests */ }

    @Override
    public void setTransferLock(String domain, String providerOrderId, boolean locked) {
        count("lock", domain);
        ProviderException forced = forcedErrors.remove("lock:" + domain);
        if (forced != null) throw forced;
        transferLocked.put(domain, locked);
    }

    public boolean isTransferLocked(String domain) { return transferLocked.getOrDefault(domain, true); }

    @Override
    public String resetAuthCode(String domain, String providerOrderId) {
        count("authcode", domain);
        ProviderException forced = forcedErrors.remove("authcode:" + domain);
        if (forced != null) throw forced;
        String code = "EPP-" + idSeq.incrementAndGet() + "-" + Integer.toHexString(domain.hashCode());
        authCodes.put(domain, code);
        return code;
    }

    public String lastAuthCode(String domain) { return authCodes.get(domain); }

    @Override
    public DnsRecord createDnsRecord(String domain, String type, String name, String value, Integer ttlSeconds) {
        count("dns", domain);
        if (dnsCreateFails) throw ProviderException.providerDown("DNS zone API unavailable");
        DnsRecord r = new DnsRecord("rec-" + idSeq.incrementAndGet(), type, name, value, ttlSeconds);
        dnsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(r);
        return r;
    }

    @Override
    public List<DnsRecord> getDnsRecords(String domain) {
        return dnsByName.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public void updateDnsRecord(String domain, String recordId, String value, Integer ttlSeconds) {
        for (List<DnsRecord> recs : dnsByName.values())
            for (int i = 0; i < recs.size(); i++)
                if (recs.get(i).id().equals(recordId)) recs.set(i, new DnsRecord(recordId, recs.get(i).type(), recs.get(i).name(), value, ttlSeconds));
    }

    @Override
    public void deleteDnsRecord(String domain, String recordId) {
        for (List<DnsRecord> recs : dnsByName.values()) recs.removeIf(r -> r.id().equals(recordId));
    }

    @Override
    public List<String> getSupportedTlds() { return List.copyOf(pricing.isEmpty() ? Set.of("com", "net", "org") : pricing.keySet()); }

    private void count(String op, String key) { callCounts.merge(op + ":" + key, 1, Integer::sum); }

    // ---- DnsVerifier: reads back exactly what createDnsRecord stored, so the runner's own VERIFYING step (which
    // goes through the real DomainService.verify -> tryVerify -> DnsVerifier.txtRecords path) succeeds once the
    // TXT record has actually been "created" through this same fake. --------------------------------------------

    @Override
    public List<String> txtRecords(String name) {
        return dnsByName.getOrDefault(name, List.of()).stream().filter(r -> "TXT".equals(r.type())).map(DnsRecord::value).toList();
    }

    @Override
    public List<String> cnames(String name) {
        return dnsByName.getOrDefault(name, List.of()).stream().filter(r -> "CNAME".equals(r.type())).map(DnsRecord::value).toList();
    }

    @Override
    public List<String> addresses(String name) {
        return dnsByName.getOrDefault(name, List.of()).stream().filter(r -> "A".equals(r.type())).map(DnsRecord::value).toList();
    }
}

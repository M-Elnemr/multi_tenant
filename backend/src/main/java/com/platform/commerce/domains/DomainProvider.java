package com.platform.commerce.domains;

import java.time.Instant;
import java.util.List;

/**
 * Registrar-agnostic abstraction (one real implementation today: {@link ResellerClubProvider}; {@link FakeDomainProvider}
 * stands in for every automated test). Nothing above this interface knows it is talking to ResellerClub, so another
 * registrar (OpenSRS, ...) can be added later without touching {@code QuoteService} / {@code DomainOrderService} /
 * {@code DomainOrderRunner}.
 *
 * Every method may throw {@link ProviderException}; callers decide what to do from its stable {@code code()} and
 * {@code retryable()} flag, never from parsing a message string.
 */
public interface DomainProvider {
    String code();

    record AvailabilityResult(String domain, boolean available, boolean premium, long providerPriceMinor, String currency) {}

    /** One live lookup per candidate domain (already-formed "label.tld" strings). */
    List<AvailabilityResult> checkAvailability(List<String> domains);

    record TldPrice(String tld, long registerProviderPriceMinor, long renewProviderPriceMinor, String currency) {}

    /** The registrar's own cost for registering/renewing one year of each TLD (before any platform markup). */
    List<TldPrice> getPricing(List<String> tlds);

    record RegistrantInfo(String name, String org, String email, String phone, String country, String address, String city, String state, String postalCode) {}

    record RegisterRequest(String domain, int years, RegistrantInfo registrant, List<String> nameservers) {}

    record RegisterResult(String providerOrderId, Instant expiresAt) {}

    RegisterResult registerDomain(RegisterRequest req);

    record RenewResult(String providerOrderId, Instant expiresAt) {}

    RenewResult renewDomain(String domain, String providerOrderId, int years);

    record TransferRequest(String domain, String authCode, RegistrantInfo registrant) {}

    record TransferResult(String providerOrderId, String status) {}

    TransferResult transferDomain(TransferRequest req);

    record DomainDetails(String domain, String providerOrderId, String status, Instant expiresAt, List<String> nameservers, boolean locked) {}

    /** {@code providerOrderId} may be null right after a timeout, when the only thing known for certain is the domain name. */
    DomainDetails getDomainDetails(String domain, String providerOrderId);

    void updateNameservers(String domain, String providerOrderId, List<String> nameservers);

    /**
     * Ownership protection: a registered domain is normally locked against transfer. The merchant must be able to
     * unlock it (and re-lock it) on demand, independently of actually transferring - this is the "recover my domain
     * if something goes wrong with the platform" path, not optional polish.
     */
    void setTransferLock(String domain, String providerOrderId, boolean locked);

    /** Issues a fresh EPP/auth code for an outbound transfer and returns it directly (shown once, like a webhook secret). */
    String resetAuthCode(String domain, String providerOrderId);

    record DnsRecord(String id, String type, String name, String value, Integer ttlSeconds) {}

    List<DnsRecord> getDnsRecords(String domain);

    DnsRecord createDnsRecord(String domain, String type, String name, String value, Integer ttlSeconds);

    void updateDnsRecord(String domain, String recordId, String value, Integer ttlSeconds);

    void deleteDnsRecord(String domain, String recordId);

    /** TLDs this registrar can sell (used by the admin screen when offering a new {@code domain_price_rules} row). */
    List<String> getSupportedTlds();
}

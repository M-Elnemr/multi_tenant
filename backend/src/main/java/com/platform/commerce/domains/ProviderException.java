package com.platform.commerce.domains;

/**
 * Normalized registrar error: every {@link DomainProvider} implementation (real or fake) must translate whatever
 * the transport throws into one of these stable codes, never let a raw HTTP status or provider-specific string
 * leak into {@code DomainOrderService} / {@code DomainOrderRunner}. {@code retryable()} is what the runner's
 * backoff loop and the admin "retry" action both key off.
 */
public class ProviderException extends RuntimeException {
    public enum Code { UNAVAILABLE, RATE_LIMITED, PROVIDER_DOWN, ALREADY_REGISTERED, INVALID_CONTACT, NOT_FOUND, TIMEOUT, UNKNOWN }

    private final Code code;
    private final boolean retryable;

    public ProviderException(Code code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public Code code() { return code; }

    public boolean retryable() { return retryable; }

    public static ProviderException unavailable(String domain) { return new ProviderException(Code.UNAVAILABLE, "Domain not available: " + domain, false); }
    public static ProviderException alreadyRegistered(String domain) { return new ProviderException(Code.ALREADY_REGISTERED, "Domain already registered: " + domain, false); }
    public static ProviderException invalidContact(String reason) { return new ProviderException(Code.INVALID_CONTACT, reason, false); }
    public static ProviderException rateLimited(String message) { return new ProviderException(Code.RATE_LIMITED, message, true); }
    public static ProviderException providerDown(String message) { return new ProviderException(Code.PROVIDER_DOWN, message, true); }
    public static ProviderException timeout(String message) { return new ProviderException(Code.TIMEOUT, message, true); }
    public static ProviderException notFound(String message) { return new ProviderException(Code.NOT_FOUND, message, false); }
    public static ProviderException unknown(String message) { return new ProviderException(Code.UNKNOWN, message, true); }

    /** What the admin "retry" action and {@code DomainOrderRunner}'s own backoff key off - a stable, code-only decision, never re-parsed from a message. */
    public static boolean isRetryableCode(String code) {
        return code != null && java.util.Set.of("RATE_LIMITED", "PROVIDER_DOWN", "TIMEOUT", "UNKNOWN").contains(code);
    }
}

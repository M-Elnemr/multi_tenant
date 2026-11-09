package com.platform.commerce.domains;

import com.platform.shared.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one on/off switch for everything that would otherwise touch a real registrar. Defaults to {@code false}:
 * until a real ResellerClub account, KYC and a funded deposit exist and credentials are set, every purchase entry
 * point refuses with {@code REGISTRAR_NOT_CONFIGURED} before the {@link DomainProvider} bean is ever called -
 * nothing is ever faked as live.
 */
@Component
public class RegistrarGate {
    private final DomainProvider provider;
    private final boolean enabled;

    public RegistrarGate(DomainProvider provider, @Value("${app.domains.registrar.enabled:false}") boolean enabled) {
        this.provider = provider;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** The wired provider bean (the real {@link ResellerClubProvider}, or a test's {@link FakeDomainProvider}). Only call this after {@link #require()} or an explicit {@link #enabled()} check. */
    public DomainProvider provider() { return provider; }

    /** Throws 409 {@code REGISTRAR_NOT_CONFIGURED} when purchases are not open; otherwise returns the provider to call. */
    public DomainProvider require() {
        if (!enabled) throw new BusinessException(HttpStatus.CONFLICT, "REGISTRAR_NOT_CONFIGURED", "Domain purchases are not available yet");
        return provider;
    }
}

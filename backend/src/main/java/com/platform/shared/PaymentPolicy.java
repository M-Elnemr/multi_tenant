package com.platform.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform-wide switch for online card payments. Off by default: stores take cash on delivery and clinics take cash at the clinic.
 * The card code paths stay in place (behind this switch) for when a real gateway is integrated.
 */
@Component
public class PaymentPolicy {
    private final boolean cardEnabled;

    public PaymentPolicy(@Value("${app.payments.card-enabled:false}") boolean cardEnabled) { this.cardEnabled = cardEnabled; }

    public boolean cardEnabled() { return cardEnabled; }
}

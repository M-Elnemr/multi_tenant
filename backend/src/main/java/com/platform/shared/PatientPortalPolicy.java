package com.platform.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Patient accounts (created by the clinic, phone + password) are on. Online booking by patients is off for now (it needs a proper business
 * model first): BOOKING_ENABLED=true brings it back.
 */
@Component
public class PatientPortalPolicy {
    private final boolean enabled;
    private final boolean booking;

    public PatientPortalPolicy(@Value("${app.patient-portal.enabled:true}") boolean enabled, @Value("${app.booking.enabled:false}") boolean booking) {
        this.enabled = enabled;
        this.booking = booking;
    }

    public boolean enabled() { return enabled; }

    public boolean bookingEnabled() { return enabled && booking; }

    public void requireEnabled() {
        if (!enabled) throw new BusinessException(HttpStatus.NOT_FOUND, "PORTAL_DISABLED", "Patient accounts are not available");
    }

    public void requireBooking() {
        if (!bookingEnabled()) throw new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_DISABLED", "Online booking is not available yet");
    }
}

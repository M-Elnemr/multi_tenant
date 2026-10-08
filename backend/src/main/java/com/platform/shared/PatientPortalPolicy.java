package com.platform.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Patient accounts (portal, activation PINs, patient login) are switched off for now: the clinic works from its own dashboard. Flip PATIENT_PORTAL_ENABLED to bring them back. */
@Component
public class PatientPortalPolicy {
    private final boolean enabled;

    public PatientPortalPolicy(@Value("${app.patient-portal.enabled:false}") boolean enabled) { this.enabled = enabled; }

    public boolean enabled() { return enabled; }

    public void requireEnabled() {
        if (!enabled) throw new BusinessException(HttpStatus.NOT_FOUND, "PORTAL_DISABLED", "Patient accounts are not available");
    }
}

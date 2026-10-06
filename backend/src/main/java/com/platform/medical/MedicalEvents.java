package com.platform.medical;

import java.util.UUID;

public final class MedicalEvents {
    private MedicalEvents() {}

    public record AppointmentChanged(UUID tenantId, UUID appointmentId, UUID patientId, String status) {}

    public record PatientUpdate(UUID tenantId, UUID patientId, String kind) {}   // PRESCRIPTION_ISSUED, LAB_RESULT_UPLOADED, ...
}

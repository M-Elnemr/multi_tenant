package com.platform.medical;

import java.util.UUID;

public final class MedicalEvents {
    private MedicalEvents() {}

    public record AppointmentChanged(UUID tenantId, UUID appointmentId, UUID patientId, String status) {}

    /** A patient (or guardian) uploaded a result or marked a requested test as done: the reviewing doctors are told. */
    public record LabResultFromPatient(UUID tenantId, UUID patientId, UUID labOrderId, boolean markedDone) {}

    public record PatientUpdate(UUID tenantId, UUID patientId, String kind) {}   // PRESCRIPTION_ISSUED, LAB_RESULT_UPLOADED, ...
}

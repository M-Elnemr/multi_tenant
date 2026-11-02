package com.platform.legal;

import java.time.Instant;
import java.util.UUID;

public final class LegalEvents {
    private LegalEvents() {}

    /** A visitor asked for a session on the website (autoApproved: the office takes requests without review). */
    public record BookingRequested(UUID tenantId, UUID bookingId, String name, Instant startsAt, boolean autoApproved) {}

    public record BookingCancelled(UUID tenantId, UUID bookingId, String name, Instant startsAt) {}

    /** The office approved, denied or moved a booking that belongs to a client (tells the client's phone). */
    public record BookingDecided(UUID tenantId, UUID bookingId, UUID clientId, String status, Instant startsAt, boolean moved, String note) {}
}

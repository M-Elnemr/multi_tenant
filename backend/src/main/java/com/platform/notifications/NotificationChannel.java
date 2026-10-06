package com.platform.notifications;

/** A delivery channel for outbox messages (spec 18/56). Paid channels (SMS, WhatsApp, push) are optional add-ons plugged in as beans. */
public interface NotificationChannel {
    String code();

    /** Returns normally on success; throws to have the message retried. */
    void send(String to, String subject, String body) throws Exception;
}

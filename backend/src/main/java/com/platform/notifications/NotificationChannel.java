package com.platform.notifications;

/** A delivery channel for outbox messages (spec 18/56). Paid channels (SMS, WhatsApp, push) are optional add-ons plugged in as beans. */
public interface NotificationChannel {
    String code();

    /** Returns normally on success; throws to have the message retried. */
    void send(String to, String subject, String body) throws Exception;

    /** Same, with a JSON payload for channels that can carry one (phone push). Others ignore it. */
    default void send(String to, String subject, String body, String payloadJson) throws Exception { send(to, subject, body); }
}

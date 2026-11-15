package com.platform.chat;

/**
 * What a message says about the message it answers, as it arrives from a channel. {@code messageId}: our own id (the website sends it; it is validated against the chat and the
 * shop before anything uses it). {@code providerMid}: the platform's id of the quoted message (WhatsApp {@code context.id}, Messenger/Instagram {@code reply_to.mid}, Evolution
 * {@code stanzaId}). {@code quote}: what the platform itself says the quoted message contained (only Evolution sends this); it is used when we hold no copy.
 * Nothing is invented: a platform that sends no reference gives no {@code ReplyRef}.
 */
public record ReplyRef(Long messageId, String providerMid, Quote quote) {
    /** The quoted message's content as a platform reported it or as we stored it. {@code sender}: "customer", "assistant" (the AI), "owner" (the shop team) or "shop" (the shop, not known which). */
    public record Quote(String sender, String kind, String text, boolean hasMedia, String at, String source) {}

    public static ReplyRef ofId(long id) { return new ReplyRef(id, null, null); }

    public static ReplyRef ofMid(String mid, Quote quote) { return new ReplyRef(null, mid == null || mid.isBlank() ? null : mid.trim(), quote); }

    public boolean empty() { return messageId == null && providerMid == null && quote == null; }
}

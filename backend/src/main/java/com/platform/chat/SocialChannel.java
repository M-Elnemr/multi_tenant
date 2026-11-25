package com.platform.chat;

import java.util.Map;

/** One more network the assistant answers on (Telegram Business, TikTok, X, Snapchat): how a message goes out on it. The shared inbound / reply flow is {@link SocialBridge}. */
public interface SocialChannel {
    /** The value stored in chat.channels.platform and chat.conversations.channel. */
    String platform();

    /** Longest text the network accepts in one message. */
    int maxLength();

    /**
     * Sends text (with a picture when {@code imageUrl} is set; the text is then its caption). {@code channel} is the chat.channels row (external_id, token_enc, owner_ref...).
     * Returns the network's id of the message, or null when it gives none; throws when the network refused.
     */
    String send(Map<String, Object> channel, String to, String text, String imageUrl);

    /** Shows «typing…» to the customer for a few seconds ({@link Typing}); a network without it keeps this default and {@link #typingRepeatMs()} 0. */
    default void typing(Map<String, Object> channel, String to) { }

    /** How often the typing signal is sent again while a reply is written (0: the network has no typing signal). */
    default long typingRepeatMs() { return 0; }

    /** Hours after the customer's last message during which the network lets the business answer (0 = no limit). */
    default int windowHours() { return 23; }
}

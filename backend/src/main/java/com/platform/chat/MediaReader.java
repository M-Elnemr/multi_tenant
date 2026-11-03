package com.platform.chat;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Turns a customer's photo or voice note into text the seller model reads like any other message: the cheap text model keeps the chat, a model that sees and hears only reads
 * the media (a few seconds, a fraction of one reply's cost). A seam so tests never touch the network.
 */
public interface MediaReader {
    enum Kind { IMAGE, AUDIO }

    /** One photo or voice note: its bytes are fetched only when read; {@code mime} as the channel gave it; {@code caption} what the customer typed with it, or null. */
    record Media(Kind kind, Supplier<byte[]> bytes, String mime, String caption) {}

    /** Prefix of a photo's description in the chat. */
    String PHOTO = "[صورة من العميل: ";
    /** Prefix of a voice note written out (the words stay the customer's own, so every check reads them as such). */
    String VOICE = "🎤 ";

    /** False when switched off (AI_MEDIA=off) or without a key: photos and voice notes get the "please type" answer as before. */
    boolean enabled();

    /** The text for the chat: «[صورة من العميل: …]» and the caption, or «🎤 » and the voice note's words; the caption alone, or null, when it could not be read. */
    String read(UUID tenantId, String channel, Media media);

    /** GET {@code url} (with a bearer token when given) and returns the body; throws when it fails or is larger than {@code maxBytes}. */
    byte[] download(String url, String bearerToken, int maxBytes);
}

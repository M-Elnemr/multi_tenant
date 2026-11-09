package com.platform.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends a message the shop wrote (or the assistant worded for the shop) to a customer outside a live reply: on WhatsApp (the shop's own linked number only; the official Cloud API is no longer used for sending), Messenger or
 * Instagram, or leaves it in the website chat for when the customer looks again. Used for the owner's answers to flagged questions and for the owner's own replies in the inbox.
 */
@Component
public class ChatDelivery {
    /** {@code delivery}: SENT, SAVED (website chat: shown when the customer looks) or FAILED, with a note for the owner when it failed. */
    public record Result(String delivery, String note) {}

    private final EvolutionService evolution;
    private final MetaChannelService meta;
    private final ProductShare share;

    public ChatDelivery(EvolutionService evolution, MetaChannelService meta, ProductShare share) {
        this.evolution = evolution;
        this.meta = meta;
        this.share = share;
    }

    public Result send(UUID tenantId, String channel, String externalId, String text, List<Map<String, Object>> products) { return send(tenantId, channel, externalId, text, products, null); }

    /** Same, with a picture the shop attached (sent with the text as its caption; the website chat shows it from the saved message). */
    public Result send(UUID tenantId, String channel, String externalId, String text, List<Map<String, Object>> products, String imageUrl) {
        if (channel == null || externalId == null) return new Result("SAVED", null);
        boolean image = imageUrl != null && !imageUrl.isBlank();
        if ("WHATSAPP".equals(channel)) {
            if (!evolution.connected(tenantId))
                return new Result("FAILED", "Your own WhatsApp number is not connected, so the message could not be sent. Link your number in the WhatsApp settings, or copy the message and send it from your phone.");
            boolean ok;
            {
                ok = true;
                if (image) ok = evolution.deliver(tenantId, externalId, text, imageUrl) != EvolutionService.Delivery.FAILED;
                else for (String part : MetaChannelService.chunks(text, 3000)) ok &= evolution.sendFree(tenantId, externalId, part);
                if (ok) for (var card : share.pick(tenantId, text, products == null ? List.of() : products)) {   // the pictures of the products the message mentions, like in any reply
                    if (evolution.deliver(tenantId, externalId, card.caption(), card.imageUrl()) == EvolutionService.Delivery.FAILED && card.imageUrl() != null) evolution.deliver(tenantId, externalId, card.caption(), null);
                }
            }
            return ok ? new Result("SENT", null)
                      : new Result("FAILED", "The message could not be sent on WhatsApp. Copy the message and send it from your phone.");
        }
        if ("FACEBOOK".equals(channel) || "INSTAGRAM".equals(channel)) {
            boolean ok = meta.deliver(tenantId, channel, externalId, text, image ? imageUrl : null);
            return ok ? new Result("SENT", null) : new Result("FAILED", "The message could not be sent: the 24-hour window with this customer is closed or the page is not connected. Copy the message and reply from the page inbox.");
        }
        return new Result("SAVED", null);   // the website chat: the message is in the chat, and an open chat window picks it up
    }
}

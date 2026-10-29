package com.platform.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sends a message the shop wrote (or the assistant worded for the shop) to a customer outside a live reply: on WhatsApp (the shop's linked number, else the Cloud API), Messenger or
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

    public Result send(UUID tenantId, String channel, String externalId, String text, List<Map<String, Object>> products) {
        if (channel == null || externalId == null) return new Result("SAVED", null);
        if ("WHATSAPP".equals(channel)) {
            boolean ok;
            if (evolution.connected(tenantId)) {
                ok = true;
                for (String part : MetaChannelService.chunks(text, 3000)) ok &= evolution.sendFree(tenantId, externalId, part);
                if (ok) for (var card : share.pick(tenantId, text, products == null ? List.of() : products)) {   // the pictures of the products the message mentions, like in any reply
                    if (evolution.deliver(tenantId, externalId, card.caption(), card.imageUrl()) == EvolutionService.Delivery.FAILED && card.imageUrl() != null) evolution.deliver(tenantId, externalId, card.caption(), null);
                }
            } else ok = meta.deliver(tenantId, "WHATSAPP", externalId, text);
            return ok ? new Result("SENT", null)
                      : new Result("FAILED", "The message could not be sent on WhatsApp (the number is not linked, or the 24-hour window with this customer is closed). Copy the message and send it from your phone.");
        }
        if ("FACEBOOK".equals(channel) || "INSTAGRAM".equals(channel)) {
            boolean ok = meta.deliver(tenantId, channel, externalId, text);
            return ok ? new Result("SENT", null) : new Result("FAILED", "The message could not be sent: the 24-hour window with this customer is closed or the page is not connected. Copy the message and reply from the page inbox.");
        }
        return new Result("SAVED", null);   // the website chat: the message is in the chat, and an open chat window picks it up
    }
}

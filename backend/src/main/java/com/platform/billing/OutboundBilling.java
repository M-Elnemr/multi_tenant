package com.platform.billing;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The one place that charges for an outbound message: a WhatsApp confirmation, reminder, offer or ad, a Messenger / Instagram ad, an AI comment reply and an AI private message each cost the same small amount
 * (0.1 EGP by default). The charge is taken BEFORE sending, so a shop with no credit sends nothing, and it is given back when the send fails. Callers must not debit on their own, or the shop pays twice.
 */
@Service
public class OutboundBilling {
    private final WalletService wallet;
    private final long priceMinor;

    public OutboundBilling(WalletService wallet, @Value("${app.messages.price-minor:10}") long priceMinor) {
        this.wallet = wallet;
        this.priceMinor = priceMinor;
    }

    /** Price of one outbound message, in piasters. */
    public long priceMinor() { return priceMinor; }

    /** True when the balance covers {@code n} more messages. */
    public boolean affordable(UUID tenantId, long n) { return wallet.balance(tenantId) >= n * priceMinor; }

    /** Takes the price of one message; false (nothing changes) when the shop has no credit left. */
    public boolean charge(UUID tenantId, String ref, String note) { return wallet.tryDebit(tenantId, priceMinor, "MESSAGE", ref, note); }

    /** Gives the price of one message back (the send failed). */
    public void refund(UUID tenantId, String ref, String note) { wallet.credit(tenantId, priceMinor, "REFUND", ref, note, null); }

    private final java.util.concurrent.ConcurrentHashMap<String, Long> toldNoCredit = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tells the shop's owner that messages were not sent for lack of credit: at most twice a day per shop and kind of message. Never throws. */
    public void notifyNoCredit(UUID tenantId, String kind, String title, String body) {
        long now = System.currentTimeMillis();
        Long last = toldNoCredit.get(tenantId + "|" + kind);
        if (last != null && now - last <= 12 * 3_600_000L) return;
        toldNoCredit.put(tenantId + "|" + kind, now);
        try { wallet.notifyOwner(tenantId, title, body, "WALLET_LOW"); } catch (RuntimeException ignored) { /* best effort */ }
    }
}

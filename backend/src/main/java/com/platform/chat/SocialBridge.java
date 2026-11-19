package com.platform.chat;

import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The same assistant, the same wallet charging and the same safeguards as on Facebook / Instagram / WhatsApp, for the networks added later (Telegram Business, TikTok, X, Snapchat):
 * a customer's quick lines are joined and answered once, a suspended shop answers nobody, the AI must be switched on for the network, and each AI conversation is charged by
 * {@link ChatService} exactly as before. A network only has to say how to send ({@link SocialChannel}) and how its webhook looks.
 */
@Service
public class SocialBridge {
    private static final Logger log = LoggerFactory.getLogger(SocialBridge.class);

    private final JdbcClient jdbc;
    private final TokenCrypto crypto;
    private final ChatService chat;
    private final LoginThrottle throttle;
    private final ProductShare share;
    private final Map<String, SocialChannel> networks;
    private final ExecutorService pool;
    private final java.util.concurrent.ScheduledExecutorService holder = Executors.newSingleThreadScheduledExecutor();
    private final InboundBurst burst;

    public SocialBridge(JdbcClient jdbc, TokenCrypto crypto, ChatService chat, LoginThrottle throttle, ProductShare share, List<SocialChannel> channels,
                        @Value("${app.meta.async:true}") boolean async, @Value("${app.chat.burst-ms:5000}") long burstMs) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.chat = chat;
        this.throttle = throttle;
        this.share = share;
        this.networks = new java.util.LinkedHashMap<>();
        for (SocialChannel c : channels) networks.put(c.platform(), c);
        this.pool = async ? Executors.newFixedThreadPool(4) : null;
        this.burst = pool == null ? new InboundBurst(holder, Runnable::run, 0, 0) : new InboundBurst(holder, pool, burstMs, 15_000);
    }

    public boolean handles(String platform) { return networks.containsKey(platform); }

    public SocialChannel network(String platform) { return networks.get(platform); }

    private boolean running(UUID tenantId) { return jdbc.sql("SELECT " + com.platform.shared.TenantState.running("CAST(:t AS uuid)")).param("t", tenantId).query(Boolean.class).single(); }

    /** The connected account (chat.channels row) a webhook is about, or null when none is connected and active. */
    public Map<String, Object> channelRow(String platform, String accountId) {
        return jdbc.sql("SELECT tenant_id, external_id, token_enc, owner_ref, status FROM chat.channels WHERE platform = :p AND external_id = :e").param("p", platform).param("e", accountId)
                .query().listOfRows().stream().findFirst().filter((r) -> "ACTIVE".equals(r.get("status"))).orElse(null);
    }

    /** A customer's text message arrived on the connected account {@code accountId} of {@code platform}. Duplicate deliveries are ignored. */
    public void inbound(String platform, String accountId, String sender, String mid, String text, String name) {
        if (accountId == null || accountId.isEmpty() || sender == null || sender.isEmpty() || mid == null || mid.isEmpty()) return;
        var row = channelRow(platform, accountId);
        if (row == null) return;
        if (jdbc.sql("INSERT INTO chat.meta_events (mid) VALUES (:m) ON CONFLICT DO NOTHING").param("m", platform + "|" + mid).update() == 0) return;
        String key = platform + "|" + accountId + "|" + sender;
        ChatService.InboundMeta meta = new ChatService.InboundMeta(mid, null);
        if (burst.enabled() && text != null && !text.isBlank()) { burst.add(key, text, meta, (joined, metas) -> respond(platform, row, sender, joined, name, ChatService.input(metas))); return; }
        respond(platform, row, sender, text, name, ChatService.input(List.of(meta)));
    }

    private void respond(String platform, Map<String, Object> row, String sender, String text, String name, ChatService.ReplyInput in) {
        try { respondNow(platform, row, sender, text, name, in); } finally { ReplyTrace.completeDeferred(); }
    }

    private void respondNow(String platform, Map<String, Object> row, String sender, String text, String name, ChatService.ReplyInput in) {
        UUID tenantId = (UUID) row.get("tenant_id");
        if (!running(tenantId)) return;   // a suspended, cancelled or archived shop does not answer customers
        SocialChannel net = networks.get(platform);
        boolean aiOn = chat.enabled(tenantId, platform);
        if (!aiOn) return;
        String answer;
        List<ProductShare.Card> cards = List.of();
        if (text == null || text.isBlank()) {
            answer = "أستطيع قراءة الرسائل النصية فقط، {اكتب|اكتبي} لي ما {تحتاجه|تحتاجيه} 🙏 / I can only read text messages, please type what you need.";
        } else {
            try {
                throttle.check("chat-social|" + platform + "|" + sender, 40, Duration.ofMinutes(10));
                boolean arabic = text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
                var hold = holder.schedule(() -> {
                    try { net.send(row, sender, arabic ? "لحظات معايا... 🌸" : "One moment, I am checking for you... 🌸", null); } catch (RuntimeException ignored) { /* best effort */ }
                }, 5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    var reply = chat.reply(tenantId, platform, sender, null, text.length() > 1000 ? text.substring(0, 1000) : text, name, in);
                    if (reply.text() == null || reply.text().isBlank()) return;   // the shop team took this chat over: nothing goes out automatically
                    answer = reply.text();
                    cards = share.pick(tenantId, answer, reply.products());
                    if (reply.order() != null && reply.order().get("accountNotice") instanceof String n && !n.isBlank()) answer += "\n\n" + n;
                    if (reply.order() != null && !Boolean.TRUE.equals(reply.order().get("test"))) answer += orderLinks(tenantId, reply.order());
                } finally {
                    hold.cancel(false);
                }
            } catch (BusinessException e) {
                log.debug("{} message ignored: {}", platform, e.getMessage());
                return;
            }
        }
        try {
            for (String message : ChatService.messages(com.platform.shared.Gender.MALE.apply(answer)))
                for (String part : MetaChannelService.chunks(message, net.maxLength())) ReplyTrace.outbound(platform, platform.toLowerCase(), "text", () -> net.send(row, sender, part, null));
        } catch (RuntimeException e) {
            log.warn("Could not send the {} reply: {}", platform, e.getMessage());
        }
        for (var card : cards) {
            try { ReplyTrace.outbound(platform, platform.toLowerCase(), "card", () -> sendCard(net, row, sender, card)); }
            catch (RuntimeException e) { log.warn("Could not send a {} product card: {}", platform, e.getMessage()); }
        }
    }

    private String sendCard(SocialChannel net, Map<String, Object> row, String to, ProductShare.Card card) {
        try { return net.send(row, to, card.caption(), card.imageUrl()); }
        catch (RuntimeException e) { if (card.imageUrl() == null) throw e; return net.send(row, to, card.caption(), null); }   // the picture could not go: its link alone
    }

    /** Links the customer needs outside the website: where to track the order, and where to pay when it is by card. */
    private String orderLinks(UUID tenantId, Map<String, Object> order) {
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional().orElse(null);
        if (host == null) return "";
        StringBuilder b = new StringBuilder();
        if (order.get("trackPath") != null) b.append("\n\n🔎 https://").append(host).append(order.get("trackPath"));
        if (order.get("paymentPath") != null) b.append("\n💳 https://").append(host).append(order.get("paymentPath"));
        return b.toString();
    }

    /**
     * A message the shop sends on its own (an owner's reply from the inbox, a follow-up, a back-in-stock alert): only while the network's window with that customer is open.
     * {@code midSink} gets the network's id of the message when it gave one.
     */
    public boolean deliver(UUID tenantId, String platform, String to, String text, String imageUrl, java.util.function.Consumer<String> midSink) {
        SocialChannel net = networks.get(platform);
        if (net == null) return false;
        try {
            var ch = jdbc.sql("SELECT tenant_id, external_id, token_enc, owner_ref, status FROM chat.channels WHERE tenant_id = :t AND platform = :p AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", platform)
                    .query().listOfRows().stream().findFirst().orElse(null);
            if (ch == null || !windowOpen(tenantId, platform, to, net.windowHours())) return false;
            String mid = null;
            List<String> parts = MetaChannelService.chunks(text == null ? "" : text, net.maxLength());
            for (int i = 0; i < parts.size(); i++) mid = net.send(ch, to, parts.get(i), i == 0 ? imageUrl : null);
            if (midSink != null && mid != null && !mid.isBlank()) midSink.accept(mid);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not send the promised {} message: {}", platform, e.getMessage());
            return false;
        }
    }

    boolean windowOpen(UUID tenantId, String platform, String to, int hours) {
        if (hours <= 0) return true;
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.conversations c JOIN chat.messages m ON m.conversation_id = c.id AND m.role = 'user' WHERE c.tenant_id = :t AND c.channel = :p AND c.external_id = :e AND m.created_at > now() - make_interval(hours => :h))")
                .param("t", tenantId).param("p", platform).param("e", to).param("h", hours).query(Boolean.class).single();
    }

    /** The decrypted secret stored with a connected account (a token); empty when none. */
    public String secret(Map<String, Object> row) { return row.get("token_enc") == null ? "" : crypto.decrypt((String) row.get("token_enc")); }

    /** Stores or refreshes a connected account of a network for a shop. */
    public void upsertChannel(UUID tenantId, UUID actor, String platform, String accountId, String name, String secret, String ownerRef, boolean active) {
        jdbc.sql("""
                INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc, owner_ref, status) VALUES (:t, :p, :e, :n, :k, :o, :s)
                ON CONFLICT (platform, external_id) DO UPDATE SET tenant_id = :t, name = :n, token_enc = :k, owner_ref = :o, status = :s
                """).param("t", tenantId).param("p", platform).param("e", accountId).param("n", name).param("k", crypto.encrypt(secret == null || secret.isBlank() ? "-" : secret)).param("o", ownerRef).param("s", active ? "ACTIVE" : "PAUSED").update();
        // a shop that had chosen its AI channels by hand answers on the new network too: connecting it is the choice
        if (active) jdbc.sql("UPDATE chat.settings SET channels = array_append(channels, CAST(:p AS varchar)) WHERE tenant_id = :t AND NOT (CAST(:p AS varchar) = ANY(channels))").param("t", tenantId).param("p", platform).update();
    }

    public List<String> allPlatforms() { return new ArrayList<>(networks.keySet()); }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.chat.WhatsAppAutoConfirmService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Automatic WhatsApp confirmation: own number only, 0.1 EGP per message charged before sending, consent, reminders, origin tracking and the Confirm tap. Meta, Evolution and the clock are faked. */
class WhatsAppAutoConfirmIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired WhatsAppAutoConfirmService auto;

    @BeforeEach
    void clean() { FakeMetaGraph.reset(); FakeAiProvider.reset(); FakeEvolutionApi.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String pnid, UUID tenantId) {}

    String uid() { return String.valueOf(100000000000L + (System.nanoTime() % 899999999999L)); }

    /** A shop with a linked own number (and a Cloud channel, which only receives), the service on and credit of 10 EGP. */
    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 0 WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", t.slug()).update();   // start without the 100 free points
        String pnid = uid();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels",
                "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\",\"pageId\":\"108291058819688\"}".formatted(pnid)).andExpect(status().isCreated());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/whatsapp", "{\"enabled\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE chat.wa_settings SET enabled_at = now() - interval '30 days' WHERE tenant_id = :t").param("t", tenantId).update();   // the tests backdate orders
        wallet.credit(tenantId, 1000, "TOPUP", null, "test", null);
        FakeMetaGraph.reset();
        FakeEvolutionApi.CALLS.clear();
        return new Shop(t, pnid, tenantId);
    }

    /** A cash order by a guest who agreed to WhatsApp contact, placed {@code hoursAgo} hours ago. Returns {orderId, orderNumber}. */
    String[] order(Shop s, long hoursAgo, boolean consent) throws Exception {
        String h = s.t().host();
        String branch = JsonPath.read(body(onHost(h, s.t().access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String variant = JsonPath.read(body(onHost(h, s.t().access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated())), "$.variants[0].id");
        String ship = JsonPath.<List<String>>read(body(onHost(h, null, "GET", "/api/v1/shop/profile", null)), "$.shippingMethods[?(@.type=='PICKUP')].id").get(0);
        String res = body(onHost(h, null, "POST", "/api/v1/shop/checkout", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}],\"shippingMethodId\":\"%s\",\"paymentMethod\":\"CASH_ON_DELIVERY\",\"address\":{\"recipientName\":\"Hana Said\",\"phone\":\"01011112222\"}}".formatted(variant, ship)).andExpect(status().isCreated()));
        String id = JsonPath.read(res, "$.orderId"), no = JsonPath.read(res, "$.orderNumber");
        jdbc.sql("UPDATE commerce.orders SET whatsapp_number = '+201077776666', whatsapp_opt_in_at = CASE WHEN :c THEN now() END, created_at = :ts WHERE id = CAST(:i AS uuid)")
                .param("c", consent).param("ts", Timestamp.from(Instant.now().minus(Duration.ofHours(hoursAgo)))).param("i", id).update();
        return new String[] {id, no};
    }

    long balance(Shop s) { return wallet.balance(s.tenantId()); }

    /** Texts sent from the shop's own number to the test customer. */
    List<String> ownSent() {
        return FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/") && String.valueOf(c.body().get("number")).equals("201077776666")).map(c -> c.body().get("text").toString()).toList();
    }

    String outStatus(Shop s) { return jdbc.sql("SELECT status FROM chat.wa_outbound WHERE tenant_id = :t").param("t", s.tenantId()).query(String.class).single(); }

    @Test
    void firstMessageAfterTheDelayThenOneReminderAndEachCostsPointOneEgp() throws Exception {
        Shop s = shop();
        String[] o = order(s, 21, true);
        Instant now = Instant.now();
        assertThat(auto.runOnce(now, s.tenantId())).isZero();   // 21 h is before the 22 h default
        assertThat(balance(s)).isEqualTo(1000);

        String[] late = order(s, 23, true);
        assertThat(auto.runOnce(now, s.tenantId())).isEqualTo(1);
        assertThat(ownSent()).hasSize(1);
        assertThat(ownSent().get(0)).contains(late[1]);
        assertThat(balance(s)).isEqualTo(990);   // 10 piasters = 0.1 EGP
        assertThat(auto.runOnce(now, s.tenantId())).isZero();   // never twice for the same order
        assertThat(balance(s)).isEqualTo(990);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND kind = 'MESSAGE' AND amount_minor = -10").param("t", s.tenantId()).query(Long.class).single()).isEqualTo(1);   // one debit, not two

        assertThat(auto.runOnce(now.plus(Duration.ofHours(23)), s.tenantId())).isEqualTo(1);   // the 21 h order becomes due
        assertThat(auto.runOnce(now.plus(Duration.ofHours(25)), s.tenantId())).isEqualTo(1);   // the first order's reminder
        assertThat(auto.runOnce(now.plus(Duration.ofHours(48)), s.tenantId())).isEqualTo(1);   // the second order's reminder
        assertThat(auto.runOnce(now.plus(Duration.ofHours(60)), s.tenantId())).isZero();       // one confirmation and one reminder per order, ever
        assertThat(balance(s)).isEqualTo(1000 - 4 * 10);
        assertThat(FakeMetaGraph.SENT).isEmpty();   // the official WhatsApp is never used for sending
        assertThat(JsonPath.<Integer>read(body(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/whatsapp/messages", null)), "$.length()")).isEqualTo(4);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/ai/whatsapp/templates", null).andExpect(status().isNotFound());   // the template endpoints are gone
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.settings.sendMode").value("FREE")).andExpect(jsonPath("$.priceMinor").value(10)).andExpect(jsonPath("$.readiness.ownNumberConnected").value(true));
        assertThat(o[0]).isNotBlank();
    }

    @Test
    void theMessageIsInTheChatAndACustomerReplyIsTaggedAsComingFromTheConfirmation() throws Exception {
        Shop s = shop();
        String[] o = order(s, 30, true);
        assertThat(auto.runOnce(Instant.now(), s.tenantId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND c.channel = 'WHATSAPP' AND c.external_id = '201077776666' AND m.kind = 'CONFIRM' AND m.role = 'assistant' AND NOT m.by_owner")
                .param("t", s.tenantId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT origin FROM chat.conversations WHERE tenant_id = :t AND external_id = '201077776666'").param("t", s.tenantId()).query(String.class).single()).isEqualTo("DIRECT");   // nobody answered yet
        assertThat(jdbc.sql("SELECT last_reply_at IS NULL FROM chat.conversations WHERE tenant_id = :t AND external_id = '201077776666'").param("t", s.tenantId()).query(Boolean.class).single()).isTrue();   // an automatic message is not a reply

        String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", s.tenantId()).query(String.class).single();
        String raw = "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"201077776666@s.whatsapp.net\",\"fromMe\":false,\"id\":\"C%s\"},\"message\":{\"conversation\":\"عايز اعدل العنوان\"}}}".formatted(inst, uniq());
        mvc.perform(post("/api/v1/chat/evolution/webhook/evolution-test-secret-0123456789").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isOk());
        var conv = jdbc.sql("SELECT origin, ai_msgs FROM chat.conversations WHERE tenant_id = :t AND external_id = '201077776666'").param("t", s.tenantId()).query().singleRow();
        assertThat(conv.get("origin")).isEqualTo("CONFIRM");
        assertThat(((Number) conv.get("ai_msgs")).intValue()).isEqualTo(1);   // one AI reply
        assertThat(o[1]).isNotBlank();
    }

    @Test
    void ownerSettingsChangeTheTimingAndTheReminderCanBeTurnedOff() throws Exception {
        Shop s = shop();
        String h = s.t().host();
        onHost(h, s.t().access(), "PUT", "/api/v1/store/ai/whatsapp", "{\"confirmAfterHours\":0}").andExpect(status().isBadRequest());
        onHost(h, s.t().access(), "PUT", "/api/v1/store/ai/whatsapp", "{\"confirmAfterHours\":5,\"reminderEnabled\":false,\"brandName\":\"Nour Store\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmAfterHours").value(5)).andExpect(jsonPath("$.reminderEnabled").value(false));
        order(s, 6, true);
        Instant now = Instant.now();
        assertThat(auto.runOnce(now, s.tenantId())).isEqualTo(1);
        assertThat(auto.runOnce(now.plus(Duration.ofHours(72)), s.tenantId())).isZero();   // reminder is off
        onHost(h, s.t().access(), "PUT", "/api/v1/store/ai/whatsapp", "{\"enabled\":false}").andExpect(status().isOk());
        order(s, 30, true);
        assertThat(auto.runOnce(now, s.tenantId())).isZero();   // service off
    }

    @Test
    void nothingIsSentWithoutConsentOrForConfirmedOrOlderOrdersOrWithoutAnOwnNumber() throws Exception {
        Shop s = shop();
        Instant now = Instant.now();
        order(s, 30, false);   // no WhatsApp consent
        String[] confirmed = order(s, 30, true);
        jdbc.sql("UPDATE commerce.orders SET confirmed_at = now() WHERE id = CAST(:i AS uuid)").param("i", confirmed[0]).update();   // the owner already confirmed it
        assertThat(auto.runOnce(now, s.tenantId())).isZero();

        order(s, 24 * 8, true);   // too old (over 7 days)
        assertThat(auto.runOnce(now, s.tenantId())).isZero();

        // orders placed before the service was switched on are never contacted
        jdbc.sql("UPDATE chat.wa_settings SET enabled_at = now() + interval '1 hour' WHERE tenant_id = :t").param("t", s.tenantId()).update();
        order(s, 30, true);
        assertThat(auto.runOnce(now, s.tenantId())).isZero();
        jdbc.sql("UPDATE chat.wa_settings SET enabled_at = now() - interval '10 days' WHERE tenant_id = :t").param("t", s.tenantId()).update();

        // no own number linked: nothing is sent, nothing is charged, the row says why
        FakeEvolutionApi.STATE = "close";
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("DISCONNECTED"));
        assertThat(auto.runOnce(now, s.tenantId())).isZero();
        assertThat(balance(s)).isEqualTo(1000);
        assertThat(outStatus(s)).isEqualTo("NO_OWN_NUMBER");
        assertThat(FakeMetaGraph.SENT).isEmpty();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.readiness.ownNumberConnected").value(false));
        FakeEvolutionApi.STATE = "open";
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        assertThat(auto.runOnce(now, s.tenantId())).isEqualTo(1);   // once the number is linked it goes out
        assertThat(outStatus(s)).isEqualTo("SENT");
    }

    @Test
    void withoutCreditNothingIsSentTheOwnerIsToldAtMostTwiceADayAndAFailedSendIsRefunded() throws Exception {
        Shop s = shop();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 0 WHERE tenant_id = :t").param("t", s.tenantId()).update();
        order(s, 30, true);
        Instant now = Instant.now();
        assertThat(auto.runOnce(now, s.tenantId())).isZero();
        assertThat(ownSent()).isEmpty();
        assertThat(outStatus(s)).isEqualTo("SKIPPED_NO_CREDIT");
        assertThat(auto.runOnce(now, s.tenantId())).isZero();   // retried on every pass, still nothing sent
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type = 'WALLET_LOW'").param("t", s.tenantId()).query(Long.class).single()).isEqualTo(1);   // one notice, not one per pass

        wallet.credit(s.tenantId(), 10, "TOPUP", null, "test", null);
        FakeEvolutionApi.FAIL_SEND = true;   // the own number fails: the shop is not charged
        assertThat(auto.runOnce(now, s.tenantId())).isZero();
        assertThat(balance(s)).isEqualTo(10);
        assertThat(outStatus(s)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND kind = 'REFUND'").param("t", s.tenantId()).query(Long.class).single()).isEqualTo(1);

        FakeEvolutionApi.FAIL_SEND = false;
        assertThat(auto.runOnce(now, s.tenantId())).isEqualTo(1);
        assertThat(balance(s)).isZero();
        assertThat(outStatus(s)).isEqualTo("SENT");
    }

    String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("testsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder h = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", b));
        return h.toString();
    }

    void whatsapp(Shop s, String from, String messageJson) throws Exception {
        String raw = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"%s\"},\"messages\":[%s]}}]}]}".formatted(s.pnid(), messageJson.replace("FROM", from).replace("MID", "wamid." + uniq()));
        mvc.perform(post("/api/v1/chat/meta/webhook").header("Host", "platform.test").header("X-Hub-Signature-256", sign(raw)).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isOk());
    }

    @Test
    void tappingConfirmConfirmsTheOrderAndStopsTheReminderAndStopOptsOut() throws Exception {
        Shop s = shop();
        String[] o = order(s, 30, true);
        Instant now = Instant.now();
        assertThat(auto.runOnce(now, s.tenantId())).isEqualTo(1);
        FakeMetaGraph.reset();

        // someone else tapping the button does nothing
        whatsapp(s, "201055554444", "{\"from\":\"FROM\",\"id\":\"MID\",\"type\":\"button\",\"button\":{\"payload\":\"CONFIRM:" + o[1] + "\",\"text\":\"تأكيد الطلب\"}}");
        assertThat(jdbc.sql("SELECT whatsapp_confirmed_at IS NOT NULL FROM commerce.orders WHERE id = CAST(:i AS uuid)").param("i", o[0]).query(Boolean.class).single()).isFalse();

        whatsapp(s, "201077776666", "{\"from\":\"FROM\",\"id\":\"MID\",\"type\":\"button\",\"button\":{\"payload\":\"CONFIRM:" + o[1] + "\",\"text\":\"تأكيد الطلب\"}}");
        assertThat(FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body().toString()).contains("تم تأكيد طلبك").contains(o[1]).contains("Mug");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/orders/" + o[0], null).andExpect(jsonPath("$.confirmedAt").exists()).andExpect(jsonPath("$.whatsappConfirmedAt").exists());
        assertThat(auto.runOnce(now.plus(Duration.ofHours(30)), s.tenantId())).isZero();   // confirmed: no reminder

        // "stop" removes consent, so a later order of that number gets nothing
        String[] next = order(s, 30, true);
        whatsapp(s, "201077776666", "{\"from\":\"FROM\",\"id\":\"MID\",\"type\":\"text\",\"text\":{\"body\":\"إيقاف\"}}");
        assertThat(jdbc.sql("SELECT whatsapp_opt_in_at IS NULL FROM commerce.orders WHERE id = CAST(:i AS uuid)").param("i", next[0]).query(Boolean.class).single()).isTrue();
        FakeMetaGraph.reset();
        assertThat(auto.runOnce(now, s.tenantId())).isZero();
    }

    @Test
    void creditIsPerShopTopUpsAreApprovedOnceAndTheBalanceNeverGoesNegative() throws Exception {
        Shop a = shop();
        Shop b = shop();
        onHost(a.t().host(), a.t().access(), "POST", "/api/v1/store/ai/whatsapp/topups", "{\"amountMinor\":500}").andExpect(status().isBadRequest());   // below 10 EGP
        String topup = JsonPath.read(body(onHost(a.t().host(), a.t().access(), "POST", "/api/v1/store/ai/whatsapp/topups", "{\"amountMinor\":5000,\"phone\":\"01011112222\"}").andExpect(status().isCreated())), "$.id");
        onHost(b.t().host(), b.t().access(), "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.topups.length()").value(0)).andExpect(jsonPath("$.balanceMinor").value(1000));

        // a shop owner cannot approve anything; the platform admin can, and only once
        onHost("platform.test", a.t().access(), "POST", "/api/v1/platform/wallet/topups/" + topup, "{\"approve\":true}").andExpect(status().isForbidden());
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", b.t().phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        String admin = JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(b.t().phone())).andExpect(status().isOk())), "$.accessToken");
        onHost("platform.test", admin, "POST", "/api/v1/platform/wallet/topups/" + topup, "{\"approve\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        onHost("platform.test", admin, "POST", "/api/v1/platform/wallet/topups/" + topup, "{\"approve\":true}").andExpect(status().isConflict());
        assertThat(balance(a)).isEqualTo(6000);
        assertThat(balance(b)).isEqualTo(1000);
        onHost("platform.test", admin, "POST", "/api/v1/platform/wallet/adjust", "{\"tenantId\":\"%s\",\"amountMinor\":-99999}".formatted(a.tenantId())).andExpect(status().isBadRequest());

        // two jobs racing for the last 1 EGP: exactly one wins
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 100 WHERE tenant_id = :t").param("t", a.tenantId()).update();
        var pool = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> wallet.tryDebit(a.tenantId(), 100, "race" + i, "race")).toList();
        long wins = pool.invokeAll(tasks).stream().filter(f -> { try { return f.get(); } catch (Exception e) { return false; } }).count();
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(balance(a)).isZero();
    }
}

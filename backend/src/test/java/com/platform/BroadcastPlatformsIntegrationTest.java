package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.chat.TokenCrypto;
import com.platform.commerce.SegmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

/** Broadcasts to several segments on several platforms (WhatsApp from the own number, Messenger, Instagram): audience, window, price, credit pause, pacing, queue states and the safety rules. */
class BroadcastPlatformsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired SegmentService segments;
    @Autowired TokenCrypto crypto;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeMetaGraph.reset(); FakeEvolutionApi.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop(long balanceMinor) throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = :b WHERE tenant_id = :t").param("b", balanceMinor).param("t", id).update();
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, id, JsonPath.read(p, "$.variants[0].id"), method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    void order(Shop s, String phone) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones(), phone);
        onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated());
    }

    void linkOwnNumber(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    void channel(Shop s, String platform) {
        jdbc.sql("INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc) VALUES (:t, :p, :e, 'page', :k)").param("t", s.id()).param("p", platform).param("e", "9" + System.nanoTime()).param("k", crypto.encrypt("EAAG-token-" + platform + "-0123456789")).update();
    }

    /** A Messenger / Instagram contact; hours = how long ago they last wrote. */
    UUID contact(Shop s, String platform, String name, int hoursAgo, String contactPhone) {
        return jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name, last_customer_at, contact_phone) VALUES (:t, :c, :e, :n, now() - make_interval(hours => :h), :p) RETURNING id")
                .param("t", s.id()).param("c", platform).param("e", "u" + uniq()).param("n", name).param("h", hoursAgo).param("p", contactPhone).query(UUID.class).single();
    }

    void gapsOff() { jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = NULL WHERE status = 'SENDING'").update(); }

    /** Runs the sender until the broadcast is no longer sending, ignoring the gap; returns how many went out. */
    int drain() {
        int sent = 0;
        for (int i = 0; i < 40; i++) {
            gapsOff();
            sent += segments.runDue();
            if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE status = 'SENDING'").query(Long.class).single() == 0) break;
        }
        return sent;
    }

    String state(String id) { return jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single(); }

    String create(Shop s, String body) throws Exception { return JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk())), "$.id"); }

    static final String ALL3 = "\"platforms\":[\"WHATSAPP\",\"FACEBOOK\",\"INSTAGRAM\"]";

    @Test
    void severalSegmentsOnSeveralPlatformsEachMessageCostsTenPiastersAndOutsideTheWindowIsCountedNotSent() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        String a = phone(), b = phone();
        order(s, a); order(s, b);
        linkOwnNumber(s);
        channel(s, "FACEBOOK"); channel(s, "INSTAGRAM");
        UUID fbLinked = contact(s, "FACEBOOK", "Mona on Messenger", 2, "+2" + a);   // the same person as WhatsApp customer a: appears once on each platform
        UUID igOpen = contact(s, "INSTAGRAM", "Insta Open", 3, null);               // never ordered: NEW
        contact(s, "INSTAGRAM", "Insta Late", 30, null);                             // window closed
        UUID igStopped = contact(s, "INSTAGRAM", "Insta Stopped", 1, null);
        jdbc.sql("UPDATE chat.conversations SET broadcast_opt_out_at = now() WHERE id = :i").param("i", igStopped).update();
        UUID igWindowAd = contact(s, "INSTAGRAM", "Insta Ad", 48, null);            // 48 h since the last message, but an ad opened a 7 day window
        jdbc.sql("UPDATE chat.conversations SET window_until = now() + interval '5 days' WHERE id = :i").param("i", igWindowAd).update();

        String res = ok(onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=VIP,NEW&platforms=WHATSAPP,FACEBOOK,INSTAGRAM&limit=50", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(res, "$.total")).intValue()).isEqualTo(7);          // 2 WhatsApp + 1 Messenger + 4 Instagram
        assertThat(((Number) JsonPath.read(res, "$.reachable")).intValue()).isEqualTo(5);      // 2 + 1 + igOpen + igWindowAd
        assertThat(((Number) JsonPath.read(res, "$.outsideWindow")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(res, "$.byPlatform.INSTAGRAM.total")).intValue()).isEqualTo(4);
        assertThat(((Number) JsonPath.read(res, "$.byPlatform.INSTAGRAM.reachable")).intValue()).isEqualTo(2);
        assertThat(((List<?>) JsonPath.read(res, "$.data[?(@.reason=='OUTSIDE_WINDOW')]")).size()).isEqualTo(1);
        assertThat(((List<?>) JsonPath.read(res, "$.data[?(@.reason=='OPTED_OUT')]")).size()).isEqualTo(1);
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=VIP&platforms=WHATSAPP,FACEBOOK,INSTAGRAM", null).andExpect(jsonPath("$.total").value(0));   // nobody is a VIP
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=NEW&platforms=FACEBOOK&q=mona", null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.data[0].platform").value("FACEBOOK"));
        onHost(h, owner, "GET", "/api/v1/store/segments?platforms=WHATSAPP", null).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.ownNumberLinked").value(true)).andExpect(jsonPath("$.messagePriceMinor").value(10)).andExpect(jsonPath("$.balanceMinor").value(1000));

        String body = "{\"segments\":[\"VIP\",\"NEW\"]," + ALL3 + ",\"message\":\"Hi {name}, new arrivals at {shop}\"}";
        String create = ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk()));
        String id = JsonPath.read(create, "$.id");
        assertThat(((Number) JsonPath.read(create, "$.audience")).intValue()).isEqualTo(7);
        assertThat(((Number) JsonPath.read(create, "$.reachable")).intValue()).isEqualTo(5);
        assertThat(((Number) JsonPath.read(create, "$.outsideWindow")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(create, "$.skipped")).intValue()).isEqualTo(1);   // the one who asked to stop
        assertThat(((Number) JsonPath.read(create, "$.costMinor")).intValue()).isEqualTo(50);
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);   // nothing is charged until a message is sent
        assertThat(drain()).isEqualTo(5);
        assertThat(state(id)).isEqualTo("DONE:");
        assertThat(wallet.balance(s.id())).isEqualTo(950L);    // 5 messages x 0.1 EGP, each once
        var evo = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/") && c.body().get("text").toString().startsWith("Hi ")).toList();
        assertThat(evo).hasSize(2);
        var meta = FakeMetaGraph.SENT.stream().filter(x -> x.path().equals("me/messages")).toList();
        assertThat(meta).hasSize(3);
        assertThat(meta.get(0).body().toString()).contains("RESPONSE").contains("Hi ");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id IN (:a, :b) AND role = 'assistant' AND kind = 'BROADCAST'").param("a", fbLinked).param("b", igOpen).query(Long.class).single()).isEqualTo(2L);
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].status").value("DONE")).andExpect(jsonPath("$[0].sentCount").value(5)).andExpect(jsonPath("$[0].chargedMinor").value(50))
                .andExpect(jsonPath("$[0].skippedOutsideWindow").value(1)).andExpect(jsonPath("$[0].segments.length()").value(2)).andExpect(jsonPath("$[0].platforms.length()").value(3)).andExpect(jsonPath("$[0].pendingCount").value(0));
        onHost(h, owner, "GET", "/api/v1/store/broadcasts/" + id + "/recipients", null).andExpect(jsonPath("$.counts.SENT").value(5)).andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.data[0].costMinor").value(10));
    }

    @Test
    void creatingNeedsTheOwnNumberAndCreditAndValidSelections() throws Exception {
        Shop s = shop(0);
        String h = s.t().host(), owner = s.t().access();
        order(s, phone());
        String wa = "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello there {name}\"}";
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", wa).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_OWN_NUMBER"));
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=WHATSAPP", null).andExpect(jsonPath("$.data[0].reachable").value(false)).andExpect(jsonPath("$.data[0].reason").value("NO_OWN_NUMBER"));
        linkOwnNumber(s);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", wa).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));
        wallet.credit(s.id(), 10, "TOPUP", null, "test", null);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello there\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"NOPE\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello there\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[\"SMS\"],\"message\":\"Hello there\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[],\"message\":\"Hello there\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", wa).andExpect(status().isOk()).andExpect(jsonPath("$.costMinor").value(10)).andExpect(jsonPath("$.reachable").value(1));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", wa).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_RUNNING"));
        onHost(h, null, "POST", "/api/v1/store/broadcasts", wa).andExpect(status().isUnauthorized());
    }

    @Test
    void whenTheCreditRunsOutTheBroadcastPausesAndResumesAfterATopUp() throws Exception {
        Shop s = shop(30);
        String h = s.t().host(), owner = s.t().access();
        for (int i = 0; i < 3; i++) order(s, phone());
        linkOwnNumber(s);
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello {name}\"}");
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 20 WHERE tenant_id = :t").param("t", s.id()).update();   // spent elsewhere meanwhile
        assertThat(drain()).isEqualTo(2);
        assertThat(state(id)).isEqualTo("PAUSED:NO_CREDIT");
        assertThat(wallet.balance(s.id())).isEqualTo(0L);
        var counts = ok(onHost(h, owner, "GET", "/api/v1/store/broadcasts/" + id + "/recipients", null));
        assertThat(((Number) JsonPath.read(counts, "$.counts.PENDING")).intValue()).isEqualTo(1);   // the one that could not be paid for is still waiting, not failed
        assertThat(((Number) JsonPath.read(counts, "$.counts.FAILED")).intValue()).isEqualTo(0);
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].pauseReason").value("NO_CREDIT")).andExpect(jsonPath("$[0].status").value("PAUSED"));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));
        wallet.credit(s.id(), 10, "TOPUP", null, "test", null);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isOk());
        assertThat(drain()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("DONE:");
        assertThat(wallet.balance(s.id())).isEqualTo(0L);
    }

    @Test
    void aFailedSendIsRefundedAndFiveInARowPause() throws Exception {
        Shop s = shop(1000);
        for (int i = 0; i < 6; i++) order(s, phone());
        linkOwnNumber(s);
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello {name}\"}");
        FakeEvolutionApi.FAIL_SEND = true;
        for (int i = 0; i < 6; i++) { gapsOff(); segments.runDue(); }
        FakeEvolutionApi.FAIL_SEND = false;
        assertThat(state(id)).isEqualTo("PAUSED:FAILURES");
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);   // every failed message was given back
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/broadcasts/" + id + "/recipients?status=FAILED", null).andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.data[0].costMinor").value(0));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isOk());
        assertThat(drain()).isEqualTo(1);
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isConflict());   // already finished
    }

    @Test
    void messagesKeepAGapOfFiveToFifteenSeconds() throws Exception {
        Shop s = shop(1000);
        for (int i = 0; i < 3; i++) order(s, phone());
        linkOwnNumber(s);
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello {name}\"}");
        ReflectionTestUtils.setField(segments, "gapMin", 5);
        ReflectionTestUtils.setField(segments, "gapMax", 15);
        try {
            for (int i = 0; i < 2; i++) {
                assertThat(segments.runDue()).isEqualTo(1);
                long wait = jdbc.sql("SELECT extract(epoch FROM next_send_at - now())::bigint FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(Long.class).single();
                assertThat(wait).isBetween(3L, 15L);
                assertThat(segments.runDue()).isEqualTo(0);   // the next one waits for the gap
                gapsOff();
            }
            onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].etaMinutes").value(1)).andExpect(jsonPath("$[0].pendingCount").value(1));   // 1 left x 10 s
        } finally { ReflectionTestUtils.setField(segments, "gapMin", 0); ReflectionTestUtils.setField(segments, "gapMax", 0); }
        drain();
    }

    @Test
    void aRecipientIsMarkedSendingFirstAndNeverStaysSendingAfterACrash() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        for (int i = 0; i < 4; i++) order(s, phone());
        linkOwnNumber(s);
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello {name}\"}");
        UUID b = UUID.fromString(id);
        List<UUID> rows = jdbc.sql("SELECT id FROM commerce.broadcast_recipients WHERE broadcast_id = :b ORDER BY id").param("b", b).query(UUID.class).list();
        // 0: being sent right now; 1: sent a moment ago; 2 and 3 wait. The listing: the one being sent, the waiting ones by id, then the finished
        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'SENDING', sent_at = now() WHERE id = :i").param("i", rows.get(0)).update();
        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'SENT', sent_at = now(), cost_minor = 10 WHERE id = :i").param("i", rows.get(1)).update();
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/broadcasts/" + id + "/recipients", null));
        assertThat((List<String>) JsonPath.read(list, "$.data[*].status")).containsExactly("SENDING", "PENDING", "PENDING", "SENT");
        assertThat((List<String>) JsonPath.read(list, "$.data[*].id")).containsExactly(rows.get(0).toString(), rows.get(2).toString(), rows.get(3).toString(), rows.get(1).toString());
        assertThat(((Number) JsonPath.read(list, "$.counts.SENDING")).intValue()).isEqualTo(1);
        // a young SENDING row is left alone and keeps the broadcast open
        assertThat(segments.runDue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM commerce.broadcast_recipients WHERE id = :i").param("i", rows.get(0)).query(String.class).single()).isEqualTo("SENDING");
        // after a crash (over two minutes) it goes back to the queue, what it was charged is given back, and it is then sent normally
        jdbc.sql("UPDATE commerce.broadcast_recipients SET sent_at = now() - interval '3 minutes', cost_minor = 10 WHERE id = :i").param("i", rows.get(0)).update();
        long before = wallet.balance(s.id());
        gapsOff();
        segments.runDue();
        assertThat(wallet.balance(s.id())).isEqualTo(before + 10 - 10);   // refunded 10, then one message (10) charged
        drain();
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'SENDING'").param("b", b).query(Long.class).single()).isEqualTo(0L);
        assertThat(state(id)).isEqualTo("DONE:");
    }

    @Test
    void someoneWhoAskedToStopOrWhoseWindowClosedIsNeverMessaged() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        String a = phone(), b = phone();
        order(s, a); order(s, b);
        linkOwnNumber(s);
        channel(s, "INSTAGRAM");
        UUID ig1 = contact(s, "INSTAGRAM", "Will Stop", 1, null);
        UUID ig2 = contact(s, "INSTAGRAM", "Will Close", 1, null);
        jdbc.sql("UPDATE commerce.customers SET broadcast_opt_out_at = now() WHERE tenant_id = :t AND phone = :p").param("t", s.id()).param("p", "+2" + a).update();
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=WHATSAPP", null).andExpect(jsonPath("$.reachable").value(1));
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\",\"INSTAGRAM\"],\"message\":\"Hello {name}\"}");
        // between creating and sending, one asks to stop and for the other the window closes
        jdbc.sql("UPDATE chat.conversations SET broadcast_opt_out_at = now() WHERE id = :i").param("i", ig1).update();
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = now() - interval '30 hours' WHERE id = :i").param("i", ig2).update();
        assertThat(drain()).isEqualTo(1);   // only customer b
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> x.path().equals("me/messages"))).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        String res = ok(onHost(h, owner, "GET", "/api/v1/store/broadcasts/" + id + "/recipients?status=SKIPPED", null));
        assertThat(((Number) JsonPath.read(res, "$.total")).intValue()).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(res, "$.data[*].error")).containsExactlyInAnyOrder("OPTED_OUT", "OUTSIDE_WINDOW");
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].skippedOutsideWindow").value(1)).andExpect(jsonPath("$[0].chargedMinor").value(10));
    }

    @Test
    void anotherShopSeesNothingOfThisAudienceOrBroadcast() throws Exception {
        Shop s = shop(1000), other = shop(1000);
        order(s, phone());
        linkOwnNumber(s);
        channel(s, "FACEBOOK");
        contact(s, "FACEBOOK", "Private Contact", 1, null);
        String id = create(s, "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\",\"FACEBOOK\"],\"message\":\"Hello {name}\"}");
        String oh = other.t().host(), ot = other.t().access();
        onHost(oh, ot, "GET", "/api/v1/store/segments/customers?segments=ALL", null).andExpect(jsonPath("$.total").value(0));
        onHost(oh, ot, "GET", "/api/v1/store/segments", null).andExpect(jsonPath("$.total").value(0));
        onHost(oh, ot, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$.length()").value(0));
        onHost(oh, ot, "GET", "/api/v1/store/broadcasts/" + id + "/recipients", null).andExpect(status().isNotFound());
        onHost(oh, ot, "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isConflict());
        drain();
    }
}

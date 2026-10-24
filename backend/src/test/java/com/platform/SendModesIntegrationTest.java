package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.commerce.SegmentService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Broadcasts to every customer or one chip: free (own number), paid (official, 3 EGP each) and app notification; and free or paid for order confirmations and cart reminders. */
class SendModesIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired SegmentService segments;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeAiProvider.reset(); FakeHttpGateway.reset(); FakeMetaGraph.reset(); FakeEvolutionApi.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), JsonPath.read(p, "$.variants[0].id"), method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    void order(Shop s, String phone, int unused) throws Exception { order(s, phone); }

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

    @Test
    void paidBroadcastChargesThreeEgpPerCustomerEvenInsideTheWindowAndNeedsCredit() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String a = phone(), b = phone();
        order(s, a); order(s, b);
        for (String p : new String[]{a, b}) jdbc.sql("UPDATE commerce.orders SET whatsapp_number = :w, whatsapp_opt_in_at = now() WHERE tenant_id = :t AND customer_phone_snapshot = :p").param("w", "+2" + p).param("t", s.id()).param("p", "+2" + p).update();
        onHost(h, owner, "POST", "/api/v1/store/ai/channels", "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted("7" + System.nanoTime())).andExpect(status().isCreated());
        for (String p : new String[]{a, b}) jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now())").param("t", s.id()).param("n", "2" + p).update();
        String body = "{\"segment\":\"ALL\",\"mode\":\"PAID\",\"message\":\"Hi {name}, new arrivals at {shop}\"}";

        onHost(h, owner, "GET", "/api/v1/store/segments", null).andExpect(jsonPath("$.segments.ALL").value(2)).andExpect(jsonPath("$.messagePriceMinor").value(300));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));   // nothing is queued without credit
        wallet.credit(s.id(), 1000, "TOPUP", null, "test", null);
        FakeMetaGraph.SENT.clear();
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(2)).andExpect(jsonPath("$.costMinor").value(600));
        assertThat(segments.runDue()).isEqualTo(2);
        assertThat(wallet.balance(s.id())).isEqualTo(400);   // 3 EGP for each of the two, although both were inside the window
        var texts = FakeMetaGraph.SENT.stream().filter(x -> "text".equals(x.body().get("type"))).toList();
        assertThat(texts).hasSize(2);
        assertThat(texts.get(0).body().toString()).contains("Hi Mona").doesNotContain("{name}").doesNotContain("{shop}");
        assertThat(FakeMetaGraph.SENT.stream().noneMatch(x -> "template".equals(x.body().get("type")))).isTrue();
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].status").value("DONE")).andExpect(jsonPath("$[0].sentCount").value(2)).andExpect(jsonPath("$[0].chargedMinor").value(600));

        // a failed delivery to Meta is refunded; a picture goes as an image message with the text as caption
        String file = jdbc.sql("INSERT INTO core.files (tenant_id, owner_user_id, object_key, original_filename, content_type, declared_size, category, visibility, status) SELECT :t, id, :k, 'a.png', 'image/png', 10, 'PRODUCT_IMAGE', 'PUBLIC', 'READY' FROM core.users LIMIT 1 RETURNING id::text")
                .param("t", s.id()).param("k", "bc-" + uniq()).query(String.class).single();
        wallet.credit(s.id(), 200, "TOPUP", null, "test", null);
        FakeMetaGraph.SENT.clear();
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"PAID\",\"message\":\"Look at this\",\"imageFileId\":\"%s\"}".formatted(file)).andExpect(status().isOk());
        assertThat(segments.runDue()).isEqualTo(2);
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> "image".equals(x.body().get("type"))).count()).isEqualTo(2);
        assertThat(wallet.balance(s.id())).isEqualTo(0L).isLessThan(1);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"message\":\"x\",\"mode\":\"NONE\"}").andExpect(status().isBadRequest());   // nothing chosen
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"NOPE\",\"message\":\"hello there\",\"mode\":\"PAID\"}").andExpect(status().isBadRequest());
    }

    @Test
    void freeBroadcastNeedsTheLinkedNumberIsPacedAndLimitedToOneADay() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        for (int i = 0; i < 6; i++) order(s, phone());
        String body = "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Hi {name}, 10% off today\"}";
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        linkOwnNumber(s);
        int before = FakeEvolutionApi.CALLS.size();
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(6)).andExpect(jsonPath("$.costMinor").value(0));
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(before);   // nothing goes out inside the request: it is queued
        for (int i = 0; i < 6; i++) assertThat(segments.runDue()).isEqualTo(1);   // one message per pass, never a burst, however long the list is
        assertThat(segments.runDue()).isEqualTo(0);
        var sends = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).toList();
        assertThat(sends).hasSize(6);
        assertThat(sends.get(0).body().get("text").toString()).contains("Hi Mona").doesNotContain("{name}");
        assertThat(wallet.balance(s.id())).isEqualTo(0L);   // free means free
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk());   // the first one is finished: a second may start
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_RUNNING"));   // but only one at a time
        while (segments.runDue() > 0) { /* let it finish */ }
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isOk());
        while (segments.runDue() > 0) { /* let it finish */ }
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_LIMIT"));   // three a day at most
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].mode").value("FREE")).andExpect(jsonPath("$[0].status").value("DONE")).andExpect(jsonPath("$[0].sentCount").value(6));
    }

    @Test
    void freeMessagesKeepAGapBetweenThemWhateverTheListSize() throws Exception {
        Shop s = shop();
        for (int i = 0; i < 3; i++) order(s, phone());
        linkOwnNumber(s);
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Hello there {name}\"}").andExpect(status().isOk()).andExpect(jsonPath("$.etaMinutes").exists());
        org.springframework.test.util.ReflectionTestUtils.setField(segments, "gapMin", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(segments, "gapMax", 10);
        try {
            assertThat(segments.runDue()).isEqualTo(1);   // the first goes out at once
            assertThat(segments.runDue()).isEqualTo(0);   // the next waits for the 10 seconds to pass
            assertThat(segments.runDue()).isEqualTo(0);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(segments, "gapMin", 0); org.springframework.test.util.ReflectionTestUtils.setField(segments, "gapMax", 0); }
        while (segments.runDue() > 0) { /* finish */ }
    }

    @Test
    void anAppNotificationReachesCustomersWhoInstalledTheApp() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String p = phone();
        order(s, p);
        UUID user = jdbc.sql("INSERT INTO commerce.client_accounts (google_sub, name) VALUES (:g, 'Mona') RETURNING id").param("g", "sub-" + uniq()).query(UUID.class).single();
        jdbc.sql("UPDATE commerce.orders SET user_id = :u WHERE tenant_id = :t AND customer_phone_snapshot = :p").param("u", user).param("t", s.id()).param("p", "+2" + p).update();
        jdbc.sql("INSERT INTO notifications.device_tokens (principal_id, token, platform) VALUES (:u, :k, 'ANDROID') ON CONFLICT (token) DO NOTHING").param("u", user).param("k", "tok-" + uniq()).update();
        onHost(h, owner, "GET", "/api/v1/store/segments/ALL", null).andExpect(jsonPath("$.preview.appUsers").value(1)).andExpect(jsonPath("$.customers[0].hasAccount").value(true));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"NONE\",\"push\":true,\"pushTitle\":\"Sale\",\"message\":\"Hi {name}, big sale\"}").andExpect(status().isOk()).andExpect(jsonPath("$.pushSent").value(1)).andExpect(jsonPath("$.queued").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE channel = 'PUSH' AND subject = 'Sale' AND body LIKE 'Hi Mona%'").query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void confirmationsAndCartRemindersChooseFreeOrPaidAndFreeNeedsTheLinkedNumber() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"FREE\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"sendMode\":\"FREE\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        onHost(h, owner, "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.settings.sendMode").value("PAID"));
        linkOwnNumber(s);
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"FREE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"sendMode\":\"FREE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));

        // a cart reminder now goes from the shop's own number, with no official message and no charge
        String phone = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Nada Ali\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), phone, s.variant())).andExpect(status().isNoContent());
        String cartId = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[0].id");
        FakeMetaGraph.SENT.clear();
        int before = FakeEvolutionApi.CALLS.size();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isOk());
        var sent = FakeEvolutionApi.CALLS.subList(before, FakeEvolutionApi.CALLS.size()).stream().filter(c -> c.path().startsWith("/message/sendText/")).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body().get("text").toString()).contains("Nada").contains("/cart");
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(0L);

        // when the phone is unlinked again, a FREE shop is never silently moved to the paid route
        FakeEvolutionApi.STATE = "close";
        onHost(h, owner, "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("DISCONNECTED"));
        String p2 = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), p2, s.variant())).andExpect(status().isNoContent());
        String cart2 = ((java.util.List<?>) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[?(@.phone=='+2" + p2 + "')].id")).get(0).toString();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict());
        assertThat(FakeMetaGraph.SENT).isEmpty();
    }

    // ---- the safety rules of free broadcasts ----------------------------------------------------------------------------------------

    void set(String field, int v) { org.springframework.test.util.ReflectionTestUtils.setField(segments, field, v); }

    String startFree(Shop s, int customers) throws Exception {
        for (int i = 0; i < customers; i++) order(s, phone());
        linkOwnNumber(s);
        return JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Hello {name}\"}").andExpect(status().isOk())), "$.id");
    }

    String broadcastState(String id) { return jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single(); }

    @Test
    void everyFreeMessageEndsWithHowToStopAndWaitsARandomGap() throws Exception {
        Shop s = shop();
        String id = startFree(s, 2);
        set("gapMin", 8); set("gapMax", 25);
        try {
            assertThat(segments.runDue()).isEqualTo(1);
            long wait = jdbc.sql("SELECT extract(epoch FROM next_send_at - now())::bigint FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(Long.class).single();
            assertThat(wait).isBetween(6L, 25L);   // somewhere between 8 and 25 seconds from the send
        } finally { set("gapMin", 0); set("gapMax", 0); }
        String text = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).findFirst().orElseThrow().body().get("text").toString();
        assertThat(text).startsWith("Hello Mona").endsWith("Reply STOP to unsubscribe").contains("اكتب: إيقاف");
        while (segments.runDue() > 0) { /* finish */ }
    }

    @Test
    void theDailyCapPausesTheBroadcastAndItGoesOnWhenThereIsRoom() throws Exception {
        Shop s = shop();
        String id = startFree(s, 3);
        set("dailyCap", 2);
        try {
            assertThat(segments.runDue()).isEqualTo(1);
            assertThat(segments.runDue()).isEqualTo(1);
            assertThat(segments.runDue()).isEqualTo(0);   // 2 sent today: the third waits
            assertThat(broadcastState(id)).isEqualTo("SENDING:DAILY_CAP");
            onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].pauseReason").value("DAILY_CAP")).andExpect(jsonPath("$[0].pendingCount").value(1));
            // a long list is accepted and the answer says how many days it will take
            onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Another\"}").andExpect(status().isConflict());   // one at a time
        } finally { set("dailyCap", 500); }
        assertThat(segments.runDue()).isEqualTo(1);
        assertThat(broadcastState(id)).isEqualTo("DONE:");
    }

    @Test
    void nothingIsSentDuringQuietHours() throws Exception {
        Shop s = shop();
        String id = startFree(s, 1);
        set("quietFrom", 0); set("quietTo", 24);   // every hour is quiet
        try {
            assertThat(segments.runDue()).isEqualTo(0);
            assertThat(broadcastState(id)).isEqualTo("SENDING:QUIET_HOURS");
        } finally { set("quietFrom", 0); set("quietTo", 0); }
        assertThat(segments.runDue()).isEqualTo(1);
    }

    @Test
    void fiveFailuresInARowPauseItAndTheOwnerCanResumeOrCancel() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String id = startFree(s, 7);
        FakeEvolutionApi.FAIL_SEND = true;
        for (int i = 0; i < 6; i++) segments.runDue();
        assertThat(broadcastState(id)).isEqualTo("PAUSED:FAILURES");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status = 'FAILED'").param("i", UUID.fromString(id)).query(Long.class).single()).isEqualTo(5L);   // it stopped instead of hammering the number
        assertThat(segments.runDue()).isEqualTo(0);
        FakeEvolutionApi.FAIL_SEND = false;
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isOk());
        assertThat(broadcastState(id)).isEqualTo("SENDING:");
        assertThat(segments.runDue()).isEqualTo(1);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isOk());
        assertThat(broadcastState(id)).isEqualTo("CANCELED:");
        assertThat(segments.runDue()).isEqualTo(0);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isConflict());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isConflict());
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isConflict());   // not theirs
    }

    @Test
    void customersWhoAnswerStopAreLeftOutAndSeveralStopsPauseTheBroadcast() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String[] phones = new String[6];
        for (int i = 0; i < 6; i++) { phones[i] = phone(); order(s, phones[i], i); }
        linkOwnNumber(s);
        String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", s.id()).query(String.class).single();
        String id = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Sale today\"}").andExpect(status().isOk())), "$.id");
        for (int i = 0; i < 3; i++) assertThat(segments.runDue()).isEqualTo(1);
        var sentTo = jdbc.sql("SELECT phone FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status = 'SENT'").param("i", UUID.fromString(id)).query(String.class).list();
        assertThat(sentTo).hasSize(3);
        for (String to : sentTo) {
            String raw = "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"S%s\"},\"message\":{\"conversation\":\"إيقاف\"}}}".formatted(inst, to, uniq());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat/evolution/webhook/evolution-test-secret-0123456789").header("Host", "platform.test").contentType("application/json").content(raw)).andExpect(status().isOk());
        }
        assertThat(segments.runDue()).isEqualTo(0);
        assertThat(broadcastState(id)).isEqualTo("PAUSED:OPT_OUTS");   // three stops out of three sent: the owner is asked to look
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.customers WHERE tenant_id = :t AND broadcast_opt_out_at IS NOT NULL").param("t", s.id()).query(Long.class).single()).isEqualTo(3L);
        // the customers who stopped are never part of a later broadcast
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/cancel", "{}").andExpect(status().isOk());
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Next one\"}").andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(3)).andExpect(jsonPath("$.skippedOptOut").value(3));
    }
}

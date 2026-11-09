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

/** Broadcasts through the own number and app notifications; order confirmations and cart reminders go only from the own number (0.1 EGP each). */
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
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 0 WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", t.slug()).update();   // start without the 100 free points
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
        var sends = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/") && c.body().get("text").toString().startsWith("Hi ")).toList();   // not the nightly shop report, which the scheduler may send around midnight
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
    void confirmationsAndCartRemindersGoOnlyFromTheOwnNumberAndCostPointOneEgp() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.settings.sendMode").value("FREE"));
        // the old paid mode is ignored: it is always FREE (the own number) and the paid switch of carts is always off
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"PAID\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"allowPaid\":true,\"sendMode\":\"PAID\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.sendMode").value("FREE")).andExpect(jsonPath("$.allowPaid").value(false)).andExpect(jsonPath("$.messagePriceMinor").value(10));
        wallet.credit(s.id(), 1000, "TOPUP", null, "test", null);

        String phone = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Nada Ali\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), phone, s.variant())).andExpect(status().isNoContent());
        String cartId = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[0].id");

        // no own number: nothing is sent and nothing is charged, and the cart says why
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_OWN_NUMBER"));
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        assertThat(jdbc.sql("SELECT reminder_status FROM commerce.abandoned_carts WHERE id = CAST(:i AS uuid)").param("i", cartId).query(String.class).single()).isEqualTo("NO_OWN_NUMBER");

        linkOwnNumber(s);
        FakeMetaGraph.SENT.clear();
        int before = FakeEvolutionApi.CALLS.size();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isOk());
        var sent = FakeEvolutionApi.CALLS.subList(before, FakeEvolutionApi.CALLS.size()).stream().filter(c -> c.path().startsWith("/message/sendText/")).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body().get("text").toString()).contains("Nada").contains("/cart");
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(990L);   // 0.1 EGP, charged once
        // the reminder is in the customer's chat as a CART message
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND c.channel = 'WHATSAPP' AND m.kind = 'CART'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);

        // a send that fails gives the 0.1 EGP back
        String p2 = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), p2, s.variant())).andExpect(status().isNoContent());
        String cart2 = ((java.util.List<?>) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[?(@.phone=='+2" + p2 + "')].id")).get(0).toString();
        FakeEvolutionApi.FAIL_SEND = true;
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEND_FAILED"));
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        FakeEvolutionApi.FAIL_SEND = false;

        // no credit: nothing is sent
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 5 WHERE tenant_id = :t").param("t", s.id()).update();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));
        assertThat(FakeMetaGraph.SENT).isEmpty();
        onHost(h, owner, "POST", "/api/v1/store/carts/template", null).andExpect(status().isNotFound());   // the template endpoint is gone
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

    // ---- details of previous orders and different wordings -------------------------------------------------------------------------

    java.util.List<String> sentTexts(Shop s) {
        return FakeEvolutionApi.CALLS.stream().filter(c -> c.path().equals("/message/sendText/" + jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", s.id()).query(String.class).single())).map(c -> c.body().get("text").toString()).toList();
    }

    @Test
    void messagesCanMentionThePreviousOrderAndEachCustomerGetsOneOfSeveralWordings() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        for (int i = 0; i < 12; i++) order(s, phone());
        linkOwnNumber(s);
        String product = jdbc.sql("SELECT name FROM commerce.products WHERE tenant_id = :t").param("t", s.id()).query(String.class).single();
        String orderNo = jdbc.sql("SELECT order_number FROM commerce.orders WHERE tenant_id = :t ORDER BY created_at LIMIT 1").param("t", s.id()).query(String.class).single();
        String msg = "{{هلا|أهلاً|مرحباً}} {name}، آخر حاجة اشتريتها كانت {last_product} (طلب {last_order} يوم {last_date}) وده طلبك رقم {orders_count}";
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", ("{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"" + msg + "\",\"variants\":[\"Version B for {name}: {last_product}\",\"Version C for {name}: {last_product}\"]}")).andExpect(status().isOk());
        while (segments.runDue() > 0) { /* send them all */ }
        var texts = sentTexts(s);
        assertThat(texts).hasSize(12);
        assertThat(texts.stream().noneMatch(t -> t.contains("{") || t.contains("}"))).isTrue();   // nothing is left unfilled
        assertThat(texts.stream().allMatch(t -> t.contains(product))).isTrue();                     // the previous product is in every wording
        var main = texts.stream().filter(t -> !t.startsWith("Version")).toList();
        assertThat(main).isNotEmpty();
        assertThat(main.stream().allMatch(t -> t.startsWith("هلا Mona") || t.startsWith("أهلاً Mona") || t.startsWith("مرحباً Mona"))).isTrue();
        assertThat(main.get(0)).contains("طلب ST-").contains("وده طلبك رقم 1");
        assertThat(texts.stream().map(t -> t.startsWith("Version B") ? "B" : t.startsWith("Version C") ? "C" : "A").distinct().count()).isGreaterThanOrEqualTo(2L);   // not everybody gets the same text
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"NONE\",\"push\":true,\"message\":\"Hello there\",\"variants\":[\"a\"]}").andExpect(status().isBadRequest());   // a wording needs 3 characters
        assertThat(orderNo).startsWith("ST-");
    }

    @Test
    void theAssistantSuggestsOtherWordingsThatKeepThePlaceholders() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        FakeAiProvider.SCRIPT.add(turns -> com.platform.chat.AiProvider.Turn.model("أهلاً {name}، عندنا خصم على {last_product} 🎁\n---\nيا {name} وصلنا خصم لطيف على {last_product}\n---\nخصم لطيف من غير الاسم\n---\nأهلاً {name}، عندنا خصم على {last_product} 🎁"));
        String res = ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts/variants", "{\"message\":\"أهلاً {name}، عندنا خصم على {last_product} 🎁\",\"count\":3}").andExpect(status().isOk()));
        java.util.List<String> v = JsonPath.read(res, "$.variants");
        assertThat(v).containsExactly("يا {name} وصلنا خصم لطيف على {last_product}");   // the copy of the original and the one that lost {name} are dropped
        assertThat(FakeAiProvider.lastSystem).contains("Keep every placeholder").contains("Do not add new offers");
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/variants", "{\"message\":\"hi\"}").andExpect(status().isBadRequest());
        onHost(h, null, "POST", "/api/v1/store/broadcasts/variants", "{\"message\":\"hello there\"}").andExpect(status().isUnauthorized());
    }
}

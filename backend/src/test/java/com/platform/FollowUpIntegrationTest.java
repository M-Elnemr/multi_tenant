package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.billing.WalletService;
import com.platform.chat.ChatService;
import com.platform.chat.FollowUpService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Free automatic follow-ups: a sequence (30 min, 3 h, 8 h after the customer's last message) scheduled when the AI answers, each step sent only while the customer stays
 * silent, cancelled by a customer reply, a takeover or closing the chat, never twice, never outside the channel's window, failures retried once then logged, and no charge.
 */
class FollowUpIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired FollowUpService followUps;
    @Autowired WalletService wallet;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); FakeEvolutionApi.reset(); }

    /** Texts the shop's own number sent (follow-ups only go from the own number). */
    java.util.List<String> ownTexts() { return FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).map(c -> c.body().get("text").toString()).toList(); }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    record Shop(Tenant t, UUID id) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/chat/followups/settings", """
                {"enabled":true,"steps":[{"delayMinutes":30,"message":"لسه محتاج مساعدة يا {name}؟"},{"delayMinutes":180,"message":"عندنا عرض النهارده 🎁"},{"delayMinutes":480,"message":"إحنا موجودين لو احتجت أي حاجة ❤️"}]}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.steps.length()").value(3));
        return new Shop(t, id(t));
    }

    String customer(Shop s, String text) {
        String wa = "2012" + (10_000_000 + System.nanoTime() % 80_000_000);
        chat.reply(s.id(), "WHATSAPP", wa, null, text, "Sara Adel");
        jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now()) ON CONFLICT (tenant_id, number) DO UPDATE SET last_inbound_at = now()").param("t", s.id()).param("n", wa).update();
        return wa;
    }

    UUID conv(Shop s, String wa) { return jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", s.id()).param("e", wa).query(UUID.class).single(); }

    /** Moves the quiet period back by {@code minutes} (the customer's message, the anchors and due times together), as if that much time had passed. */
    void age(UUID conv, int minutes) {
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = last_customer_at - make_interval(mins => :m), last_reply_at = last_reply_at - make_interval(mins => :m) WHERE id = :c").param("m", minutes).param("c", conv).update();
        jdbc.sql("UPDATE chat.auto_followups SET anchor_customer_at = anchor_customer_at - make_interval(mins => :m), due_at = due_at - make_interval(mins => :m) WHERE conversation_id = :c").param("m", minutes).param("c", conv).update();
    }

    @Test
    void aThreeStepSequenceSendsWhileTheCustomerIsSilentAndStopsWhenTheyReply() throws Exception {
        Shop s = shop();
        long credit = wallet.balance(s.id());
        String wa = customer(s, "عايز أسأل عن الشنط");
        UUID c = conv(s, wa);
        assertThat(followUps.statuses(c)).containsExactly("1:SCHEDULED", "2:SCHEDULED", "3:SCHEDULED");
        long[] offsets = jdbc.sql("SELECT extract(epoch FROM f.due_at - c.last_customer_at)::bigint FROM chat.auto_followups f JOIN chat.conversations c ON c.id = f.conversation_id WHERE f.conversation_id = :c ORDER BY step_position")
                .param("c", c).query(Long.class).list().stream().mapToLong(Long::longValue).toArray();
        assertThat(offsets[0]).isBetween(30 * 60L - 5, 31 * 60L);
        assertThat(offsets[1]).isBetween(180 * 60L - 5, 180 * 60L + 5);
        assertThat(offsets[2]).isBetween(480 * 60L - 5, 480 * 60L + 5);

        // nothing is due yet
        assertThat(followUps.runDue()).isZero();
        // 31 minutes later: step 1 goes out (with the customer's name), as a FOLLOWUP message in the chat
        age(c, 31);
        assertThat(followUps.runDue()).isEqualTo(1);
        assertThat(followUps.statuses(c)).containsExactly("1:SENT", "2:SCHEDULED", "3:SCHEDULED");
        assertThat(ownTexts().get(ownTexts().size() - 1)).contains("لسه محتاج مساعدة يا Sara");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND kind = 'FOLLOWUP'").param("c", c).query(Long.class).single()).isEqualTo(1);
        // running again sends nothing twice
        assertThat(followUps.runDue()).isZero();
        // 3 hours: step 2
        age(c, 150);
        assertThat(followUps.runDue()).isEqualTo(1);
        assertThat(followUps.statuses(c)).containsExactly("1:SENT", "2:SENT", "3:SCHEDULED");

        // the customer answers: step 3 is cancelled, the follow-up that brought them back is marked, and the AI's new answer starts a new quiet period
        chat.reply(s.id(), "WHATSAPP", wa, null, "أيوه عايز الشنطة السودا", null);
        var st = followUps.statuses(c);
        assertThat(st.subList(0, 3)).containsExactly("1:SENT", "2:SENT", "3:CANCELLED");
        assertThat(st.subList(3, 6)).containsExactly("1:SCHEDULED", "2:SCHEDULED", "3:SCHEDULED");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.auto_followups WHERE conversation_id = :c AND returned_at IS NOT NULL").param("c", c).query(Long.class).single()).isEqualTo(1);
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/chat/followups/stats", null).andExpect(jsonPath("$.sent").value(2)).andExpect(jsonPath("$.returned").value(1));
        // free: no Elmanassa charge for follow-ups (only the AI conversations themselves)
        assertThat(wallet.balance(s.id())).isEqualTo(credit - 100);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND note ILIKE '%follow%'").param("t", s.id()).query(Long.class).single()).isZero();
    }

    @Test
    void takeoverClosingTheWindowAndFailuresStopFollowUpsSafely() throws Exception {
        Shop s = shop();
        // takeover cancels what is waiting
        UUID a = conv(s, customer(s, "hello there"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/chat/conversations/" + a + "/takeover", null).andExpect(status().isOk());
        assertThat(followUps.statuses(a)).containsOnly("1:CANCELLED", "2:CANCELLED", "3:CANCELLED");

        // a closed chat gets nothing
        UUID b = conv(s, customer(s, "price please"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/chat/conversations/" + b + "/close", null).andExpect(status().isOk());
        assertThat(followUps.statuses(b)).containsOnly("1:CANCELLED", "2:CANCELLED", "3:CANCELLED");

        // the window closed before the step was due: ineligible, nothing sent
        UUID c = conv(s, customer(s, "do you deliver to Giza"));
        age(c, 24 * 60);
        int sent = ownTexts().size();
        assertThat(followUps.runDue()).isZero();
        assertThat(followUps.statuses(c).get(0)).isEqualTo("1:INELIGIBLE");
        assertThat(ownTexts()).hasSize(sent);

        // a provider failure is retried once after 5 minutes, then logged as FAILED (and the later steps do not go out)
        UUID d = conv(s, customer(s, "is it cotton"));
        age(d, 31);
        FakeEvolutionApi.FAIL_SEND = true;
        assertThat(followUps.runDue()).isZero();
        assertThat(followUps.statuses(d).get(0)).isEqualTo("1:SCHEDULED");
        jdbc.sql("UPDATE chat.auto_followups SET due_at = now() - interval '1 minute' WHERE conversation_id = :c AND step_position = 1").param("c", d).update();
        assertThat(followUps.runDue()).isZero();
        assertThat(followUps.statuses(d).get(0)).isEqualTo("1:FAILED");
        assertThat(jdbc.sql("SELECT reason FROM chat.auto_followups WHERE conversation_id = :c AND step_position = 1").param("c", d).query(String.class).single()).isNotBlank();
        FakeEvolutionApi.FAIL_SEND = false;
        age(d, 160);
        followUps.runDue();
        assertThat(followUps.statuses(d).get(1)).isEqualTo("2:CANCELLED");

        // the owner cancels one that is waiting, from the management list
        UUID e = conv(s, customer(s, "colors?"));
        String fid = jdbc.sql("SELECT id::text FROM chat.auto_followups WHERE conversation_id = :c AND step_position = 2").param("c", e).query(String.class).single();
        onHost(s.t().host(), s.t().access(), "PUT", "/api/v1/store/chat/followups/" + fid, "{\"body\":\"نزل لون جديد 😍\"}").andExpect(status().isNoContent());
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/chat/followups/" + fid + "/cancel", null).andExpect(status().isNoContent());
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/chat/followups/" + fid + "/cancel", null).andExpect(status().isConflict());
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/chat/followups?status=CANCELLED", null).andExpect(status().isOk());
    }

    @Test
    void theSequenceIsValidatedAndTurningItOffCancelsWhatIsWaiting() throws Exception {
        Shop s = shop();
        String h = s.t().host(), tok = s.t().access();
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":true,\"steps\":[{\"delayMinutes\":20,\"message\":\"x\"}]}").andExpect(status().isBadRequest());
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":true,\"steps\":[{\"delayMinutes\":1400,\"message\":\"x\"}]}").andExpect(status().isBadRequest());
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":true,\"steps\":[{\"delayMinutes\":180,\"message\":\"a\"},{\"delayMinutes\":60,\"message\":\"b\"}]}").andExpect(status().isBadRequest());
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":true,\"steps\":[{\"delayMinutes\":30,\"message\":\"m\"},{\"delayMinutes\":60,\"message\":\"m\"},{\"delayMinutes\":90,\"message\":\"m\"},{\"delayMinutes\":120,\"message\":\"m\"},{\"delayMinutes\":150,\"message\":\"m\"},{\"delayMinutes\":180,\"message\":\"m\"}]}").andExpect(status().isBadRequest());
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":true,\"steps\":[]}").andExpect(status().isBadRequest());

        UUID c = conv(s, customer(s, "hi, sizes?"));
        assertThat(followUps.statuses(c)).hasSize(3);
        onHost(h, tok, "PUT", "/api/v1/store/chat/followups/settings", "{\"enabled\":false,\"steps\":[{\"delayMinutes\":60,\"message\":\"later\"}]}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(followUps.statuses(c)).containsOnly("1:CANCELLED", "2:CANCELLED", "3:CANCELLED");
        UUID d = conv(s, customer(s, "another question"));
        assertThat(followUps.statuses(d)).isEqualTo(List.of());
    }
}

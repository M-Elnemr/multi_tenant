package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.chat.TokenCrypto;
import com.platform.chat.unofficial.OutOfWindowSender;
import com.platform.chat.unofficial.UnofficialMetaService;
import com.platform.commerce.ManualStyleRunner;
import com.platform.commerce.SegmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The manual-style route (old Messenger / Instagram contacts, unofficial, slow like a person). Verified ONLY against {@link FakeOutOfWindowSender}: no real Instagram account was used.
 * The gaps, breaks and active hours are switched off in the test configuration (the unit tests in HumanPolicyTest cover them); everything else is the real code path.
 */
@ExtendWith(OutputCaptureExtension.class)
class ManualStyleBroadcastIntegrationTest extends IntegrationTestBase {
    static final String PASSWORD = "Sup3r-Secret-Pw!9";
    static final String TOTP = "JBSWY3DPEHPK3PXP";

    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired SegmentService segments;
    @Autowired ManualStyleRunner runner;
    @Autowired UnofficialMetaService unofficial;
    @Autowired TokenCrypto crypto;

    @BeforeEach
    void clean() {
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update();
        FakeOutOfWindowSender.reset();
        FakeEvolutionApi.reset();
        ReflectionTestUtils.setField(unofficial, "enabled", true);
    }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id) {}

    Shop shop(long balance) throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = :b WHERE tenant_id = :t").param("b", balance).param("t", id).update();
        return new Shop(t, id);
    }

    void channel(Shop s, String platform) {
        jdbc.sql("INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc) VALUES (:t, :p, :e, 'page', :k)").param("t", s.id()).param("p", platform).param("e", "9" + System.nanoTime()).param("k", crypto.encrypt("EAAG-token-" + platform + "-0123456789")).update();
    }

    /** An Instagram contact the shop talked with (customer wrote, shop answered), last customer message {@code hoursAgo} ago. */
    UUID contact(Shop s, String name, int hoursAgo) {
        UUID c = jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'INSTAGRAM', :e, :n) RETURNING id").param("t", s.id()).param("e", "ig" + uniq()).param("n", name).query(UUID.class).single();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', 'do you have it in blue?')").param("c", c).update();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', 'yes we do')").param("c", c).update();
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = now() - make_interval(hours => :h) WHERE id = :c").param("h", hoursAgo).param("c", c).update();
        return c;
    }

    void connect(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/unofficial-meta/instagram/connect",
                "{\"username\":\"shop_account\",\"password\":\"" + PASSWORD + "\",\"totpSecret\":\"" + TOTP + "\",\"acceptedRisk\":true}").andExpect(status().isOk());
    }

    static final String BODY = "{\"segments\":[\"ALL\"],\"platforms\":[\"INSTAGRAM\"],\"manualPlatforms\":[\"INSTAGRAM\"],\"message\":\"Hi {name}, new offer today at {shop}\","
            + "\"variants\":[\"Hello {name}, today there is an offer at {shop}\",\"Hey {name}! {shop} has something new for you today\"]}";

    int drain() {
        int sent = 0;
        for (int i = 0; i < 12; i++) {
            jdbc.sql("UPDATE commerce.broadcasts SET manual_next_at = NULL WHERE status = 'SENDING'").update();
            sent += runner.runDue();
            if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE status = 'SENDING'").query(Long.class).single() == 0) break;
        }
        return sent;
    }

    String state(String id) { return jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single(); }

    @Test
    void anOutsideWindowContactIsSentOnlyWithAnActiveSessionAndTheFlag_chargedOnce_routeRecorded_credentialsNeverShown(CapturedOutput out) throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        channel(s, "INSTAGRAM");
        UUID late = contact(s, "Layla Late", 30);
        contact(s, "layla.ig", 40);

        // no session yet: outside the window and nothing manual to offer
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=INSTAGRAM&manual=INSTAGRAM", null)
                .andExpect(jsonPath("$.reachable").value(0)).andExpect(jsonPath("$.outsideWindow").value(2)).andExpect(jsonPath("$.data[0].reason").value("OUTSIDE_WINDOW"))
                .andExpect(jsonPath("$.byPlatform.INSTAGRAM.manualStyleReachable").value(0));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANUAL_STYLE_UNAVAILABLE"));
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.platforms.INSTAGRAM.supported").value(true)).andExpect(jsonPath("$.platforms.INSTAGRAM.connected").value(false))
                .andExpect(jsonPath("$.platforms.FACEBOOK.supported").value(false)).andExpect(jsonPath("$.platforms.INSTAGRAM.dailyCap").value(10));

        // connecting needs the risk acceptance, the feature on, and a supported platform
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/connect", "{\"username\":\"shop_account\",\"password\":\"" + PASSWORD + "\",\"acceptedRisk\":false}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RISK_NOT_ACCEPTED"));
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/facebook/connect", "{\"username\":\"x\",\"password\":\"" + PASSWORD + "\",\"acceptedRisk\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNSUPPORTED_PLATFORM"));
        FakeOutOfWindowSender.LOGIN_ERROR = "CHALLENGE";
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/connect", "{\"username\":\"shop_account\",\"password\":\"" + PASSWORD + "\",\"acceptedRisk\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHALLENGE_REQUIRED"));
        assertThat(jdbc.sql("SELECT count(*) FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isZero();   // a failed login stores nothing
        FakeOutOfWindowSender.LOGIN_ERROR = null;
        ReflectionTestUtils.setField(unofficial, "enabled", false);
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/connect", "{\"username\":\"shop_account\",\"password\":\"" + PASSWORD + "\",\"acceptedRisk\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNOFFICIAL_META_OFF"));
        ReflectionTestUtils.setField(unofficial, "enabled", true);

        String connected = ok(onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/connect",
                "{\"username\":\"shop_account\",\"password\":\"" + PASSWORD + "\",\"totpSecret\":\"" + TOTP + "\",\"acceptedRisk\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.accountName").value("shop_account")));
        String overview = ok(onHost(h, owner, "GET", "/api/v1/store/unofficial-meta", null));
        String stored = jdbc.sql("SELECT credentials_enc FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(String.class).single();
        for (String text : List.of(connected, overview, stored)) assertThat(text).doesNotContain(PASSWORD).doesNotContain(TOTP);
        assertThat(crypto.decrypt(stored)).contains(PASSWORD);   // it is stored, but only encrypted

        // the flag off: the same session is no longer usable
        ReflectionTestUtils.setField(unofficial, "enabled", false);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANUAL_STYLE_UNAVAILABLE"));
        ReflectionTestUtils.setField(unofficial, "enabled", true);

        // now it is reachable, with the manual route chosen; without it the contacts stay outside the window
        onHost(h, owner, "GET", "/api/v1/store/segments?platforms=INSTAGRAM", null).andExpect(jsonPath("$.byPlatform.INSTAGRAM.manualStyleReachable").value(2)).andExpect(jsonPath("$.manualStyle.INSTAGRAM.available").value(true));
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=INSTAGRAM", null).andExpect(jsonPath("$.reachable").value(0)).andExpect(jsonPath("$.outsideWindow").value(2));
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=INSTAGRAM&manual=INSTAGRAM", null)
                .andExpect(jsonPath("$.reachable").value(2)).andExpect(jsonPath("$.manualStyle").value(2)).andExpect(jsonPath("$.data[0].route").value("MANUAL_STYLE"));

        // at least three wordings are required
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[\"INSTAGRAM\"],\"manualPlatforms\":[\"INSTAGRAM\"],\"message\":\"Hi {name}, new offer today\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VARIANTS_REQUIRED"));
        // content rules apply at creation
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[\"INSTAGRAM\"],\"manualPlatforms\":[\"INSTAGRAM\"],\"message\":\"see http://a.com and http://b.com now\",\"variants\":[\"look at http://a.com or http://b.com\",\"visit http://a.com then http://b.com\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTENT_REJECTED"));

        String create = ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isOk()));
        String id = JsonPath.read(create, "$.id");
        assertThat(((Number) JsonPath.read(create, "$.reachable")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(create, "$.manualStyle")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(create, "$.costMinor")).intValue()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND route = 'MANUAL_STYLE'").param("b", UUID.fromString(id)).query(Long.class).single()).isEqualTo(2L);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isConflict());   // one running broadcast at a time
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);   // nothing is charged before a send

        assertThat(segments.runDue()).isZero();   // the official lane never touches a manual-style recipient
        assertThat(FakeOutOfWindowSender.SENT).isEmpty();
        assertThat(runner.runDue()).isEqualTo(1);
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        assertThat(drain()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("DONE:");
        assertThat(wallet.balance(s.id())).isEqualTo(980L);   // 0.1 EGP each, once
        assertThat(FakeOutOfWindowSender.SENT).hasSize(2);
        assertThat(FakeOutOfWindowSender.SENT.get(0).text()).isNotEqualTo(FakeOutOfWindowSender.SENT.get(1).text());   // wordings rotate: never the same text twice in a row
        assertThat(FakeOutOfWindowSender.SENT.get(0).text()).contains("STOP");
        assertThat(FakeOutOfWindowSender.SENT.stream().map(FakeOutOfWindowSender.Sent::text).anyMatch(x -> x.contains("Layla"))).isTrue();   // first name personalization
        assertThat(FakeOutOfWindowSender.SENT.stream().map(FakeOutOfWindowSender.Sent::ref)).contains("layla.ig");   // a username-like name is the recipient reference
        onHost(h, owner, "GET", "/api/v1/store/broadcasts/" + id + "/recipients?status=SENT", null).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.data[0].route").value("MANUAL_STYLE")).andExpect(jsonPath("$.data[0].costMinor").value(10));
        assertThat(jdbc.sql("SELECT count(*) FROM chat.unofficial_sessions WHERE tenant_id = :t AND sent_today = 2 AND last_sent_at IS NOT NULL").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(late).isNotNull();

        // the same people are not messaged again within 30 days by this route
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=INSTAGRAM&manual=INSTAGRAM", null).andExpect(jsonPath("$.reachable").value(0)).andExpect(jsonPath("$.data[0].reason").value("MANUAL_RECENT"));
        assertThat(out.getAll()).doesNotContain(PASSWORD).doesNotContain(TOTP);
    }

    @Test
    void aBlockSignalStopsEverythingAtOnce_sessionBlocked48Hours_ownerTold_noRetry() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        channel(s, "INSTAGRAM");
        contact(s, "One", 30); contact(s, "Two", 31); contact(s, "Three", 32);
        connect(s);
        String id = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isOk())), "$.id");
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.BLOCKED;
        assertThat(drain()).isZero();
        assertThat(state(id)).isEqualTo("PAUSED:ACCOUNT_BLOCK_SIGNAL");
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);   // the attempt was given back
        assertThat(jdbc.sql("SELECT status FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("BLOCKED");
        long hours = jdbc.sql("SELECT extract(epoch FROM blocked_until - now())::bigint / 3600 FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single();
        assertThat(hours).isBetween(47L, 48L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING'").param("b", UUID.fromString(id)).query(Long.class).single()).isEqualTo(2L);   // untouched, no retry loop
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type = 'BROADCAST_ACCOUNT_BLOCK'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.SENT;
        assertThat(runner.runDue()).isZero();   // paused: nothing goes out even though the fake would now accept
        assertThat(FakeOutOfWindowSender.SENT).isEmpty();
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/enable", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STILL_BLOCKED"));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_READY"));
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta", null).andExpect(jsonPath("$.platforms.INSTAGRAM.status").value("BLOCKED")).andExpect(jsonPath("$.platforms.INSTAGRAM.blockedUntil").isNotEmpty());
        // after the 48 hours the owner may bring it back and resume
        jdbc.sql("UPDATE chat.unofficial_sessions SET blocked_until = now() - interval '1 minute' WHERE tenant_id = :t").param("t", s.id()).update();
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/enable", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost(h, owner, "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isOk());
        assertThat(drain()).isEqualTo(2);
    }

    @Test
    void anExpiredSessionPausesAndKeepsTheContactInTheQueue_anUnreachableBridgePauses() throws Exception {
        Shop s = shop(1000);
        channel(s, "INSTAGRAM");
        contact(s, "One", 30);
        connect(s);
        String id = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isOk())), "$.id");
        FakeOutOfWindowSender.HEALTHY = false;
        assertThat(drain()).isZero();
        assertThat(state(id)).isEqualTo("PAUSED:BRIDGE_UNREACHABLE");
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        FakeOutOfWindowSender.HEALTHY = true;
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts/" + id + "/resume", "{}").andExpect(status().isOk());
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.SESSION_EXPIRED;
        assertThat(drain()).isZero();
        assertThat(state(id)).isEqualTo("PAUSED:SESSION_EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("NEEDS_LOGIN");
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING'").param("b", UUID.fromString(id)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theWarmUpCapIsEnforced_dayOneAllowsTen() throws Exception {
        Shop s = shop(1000);
        channel(s, "INSTAGRAM");
        for (int i = 0; i < 3; i++) contact(s, "C" + i, 30 + i);
        connect(s);
        String id = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isOk())), "$.id");
        jdbc.sql("UPDATE chat.unofficial_sessions SET sent_today = 9, sent_today_date = (now() AT TIME ZONE 'Africa/Cairo')::date WHERE tenant_id = :t").param("t", s.id()).update();
        assertThat(drain()).isEqualTo(1);   // the 10th of day one is the last one
        assertThat(state(id)).isEqualTo("SENDING:MS_DAILY_CAP");
        assertThat(FakeOutOfWindowSender.SENT).hasSize(1);
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/unofficial-meta", null).andExpect(jsonPath("$.platforms.INSTAGRAM.sentToday").value(10)).andExpect(jsonPath("$.platforms.INSTAGRAM.warmupDay").value(1));
        // day 2 allows 15
        jdbc.sql("UPDATE chat.unofficial_sessions SET warmup_started_at = now() - interval '1 day' WHERE tenant_id = :t").param("t", s.id()).update();
        assertThat(drain() >= 1).isTrue();
        assertThat(FakeOutOfWindowSender.SENT.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theOwnerChattingWithAContactAndAudienceLimitsKeepThemOut() throws Exception {
        Shop s = shop(1000);
        channel(s, "INSTAGRAM");
        UUID busy = contact(s, "Busy", 30);
        contact(s, "Ancient", 24 * 200);                   // last wrote 200 days ago
        UUID shy = jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'INSTAGRAM', :e, 'Shy') RETURNING id").param("t", s.id()).param("e", "ig" + uniq()).query(UUID.class).single();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', 'hi')").param("c", shy).update();   // only one message ever
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = now() - interval '30 hours' WHERE id = :c").param("c", shy).update();
        connect(s);
        String res = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=INSTAGRAM&manual=INSTAGRAM", null));
        assertThat(((Number) JsonPath.read(res, "$.reachable")).intValue()).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(res, "$.data[?(@.name=='Ancient')].reason")).containsExactly("MANUAL_TOO_OLD");
        assertThat((List<String>) JsonPath.read(res, "$.data[?(@.name=='Shy')].reason")).containsExactly("MANUAL_FEW_MESSAGES");
        String id = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", BODY).andExpect(status().isOk())), "$.id");
        // between creating and sending the owner takes the chat over
        jdbc.sql("UPDATE chat.conversations SET takeover = TRUE WHERE id = :c").param("c", busy).update();
        assertThat(drain()).isZero();
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/broadcasts/" + id + "/recipients?status=SKIPPED", null).andExpect(jsonPath("$.data[0].error").value("OWNER_ACTIVE"));
    }

    @Test
    void wipeDeletesTheCredentialsAndDropsTheSession_disableAndEnableWork() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        connect(s);
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/disable", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(unofficial.reachable(s.id(), "INSTAGRAM")).isFalse();
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/instagram/enable", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost(h, owner, "DELETE", "/api/v1/store/unofficial-meta/instagram", null).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM chat.unofficial_sessions WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isZero();
        assertThat(FakeOutOfWindowSender.LOGOUTS).containsExactly(s.id());
        onHost(h, null, "GET", "/api/v1/store/unofficial-meta", null).andExpect(status().isUnauthorized());
        // another shop never sees this one's session
        Shop other = shop(0);
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/unofficial-meta", null).andExpect(jsonPath("$.platforms.INSTAGRAM.connected").value(false));
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.chat.TokenCrypto;
import com.platform.chat.unofficial.IgBridgeClient;
import com.platform.chat.unofficial.OutOfWindowSender;
import com.platform.commerce.ManualStyleRunner;
import com.platform.commerce.ProspectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * New-customer prospects: the sync (Instagram through a fake ig-bridge, Facebook from the stored comments), dedup against existing conversations, and the PROSPECTS broadcast
 * (route MANUAL_STYLE, 0.1 EGP charge before the send, refund on failure, the stricter cold policy). Fakes only: no real account.
 */
class ProspectsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired ManualStyleRunner runner;
    @Autowired ProspectService prospects;
    @Autowired TokenCrypto crypto;

    record Shop(Tenant t, UUID id) {}

    @BeforeEach
    void clean() {
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update();
        FakeOutOfWindowSender.reset();
        FakeIgBridgeClient.reset();
    }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    Shop shop(long balance) throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = :b WHERE tenant_id = :t").param("b", balance).param("t", id).update();
        return new Shop(t, id);
    }

    void connect(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/unofficial-meta/instagram/connect", "{\"username\":\"shop_account\",\"password\":\"Sup3r-Secret-Pw!9\"}").andExpect(status().isOk());
    }

    void seedInstagram() {
        FakeIgBridgeClient.FOLLOWER_PAGES.add(List.of(FakeIgBridgeClient.user("1001", "ann"), FakeIgBridgeClient.user("1002", "bob")));
        FakeIgBridgeClient.FOLLOWER_PAGES.add(List.of(FakeIgBridgeClient.user("1003", "cat"), FakeIgBridgeClient.user("1004", "dan")));
        FakeIgBridgeClient.MEDIA.add(new IgBridgeClient.Media("m1", "CODE1", "caption", 2));
        FakeIgBridgeClient.COMMENTS.put("m1", List.of(new IgBridgeClient.Comment("c1", "how much?", FakeIgBridgeClient.user("1002", "bob")), new IgBridgeClient.Comment("c2", "love it", FakeIgBridgeClient.user("1005", "eve"))));
    }

    String syncAndWait(Shop s, String platform) throws Exception {
        return ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/prospects/sync", "{\"platform\":\"" + platform + "\"}").andExpect(status().isAccepted()));
    }

    static final String BODY = "{\"platforms\":[\"INSTAGRAM\"],\"all\":true,\"message\":\"Hi {name}, new offer today at {shop}\","
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

    @Test
    void instagramSync_listsFollowersAndCommenters_dedupsAgainstConversations_oneSyncAtATime() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        seedInstagram();
        // not connected: a clear code
        onHost(h, owner, "POST", "/api/v1/store/prospects/sync", "{\"platform\":\"INSTAGRAM\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEEDS_LOGIN"));
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=INSTAGRAM", null).andExpect(jsonPath("$.status").value("IDLE"));
        connect(s);
        // dan already talked to the page (by username), cat has the same id as a conversation: both are existing customers, not prospects
        jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'INSTAGRAM', 'igsid-x', 'Dan')").param("t", s.id()).update();
        jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'INSTAGRAM', '1003', 'Somebody')").param("t", s.id()).update();

        String res = syncAndWait(s, "INSTAGRAM");
        assertThat((String) JsonPath.read(res, "$.status")).isEqualTo("DONE");
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=INSTAGRAM", null).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.fetched").value(6)).andExpect(jsonPath("$.lastSyncedAt").isNotEmpty());
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=INSTAGRAM", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(list, "$.total")).intValue()).isEqualTo(3);   // ann, bob, eve
        assertThat(((Number) JsonPath.read(list, "$.counts.INSTAGRAM.FOLLOWER")).intValue()).isEqualTo(1);    // ann
        assertThat(((Number) JsonPath.read(list, "$.counts.INSTAGRAM.COMMENTER")).intValue()).isEqualTo(2);   // bob (follower and commenter: counted once, as commenter) and eve
        assertThat(((Number) JsonPath.read(list, "$.counts.FACEBOOK.COMMENTER")).intValue()).isZero();
        assertThat((List<String>) JsonPath.read(list, "$.items[*].username")).containsExactlyInAnyOrder("ann", "bob", "eve");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.username=='bob')].lastComment")).containsExactly("how much?");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.username=='bob')].postRef")).containsExactly("CODE1");
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=INSTAGRAM&source=COMMENTER&q=EV", null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].status").value("NEW"));
        onHost(h, owner, "GET", "/api/v1/store/prospects?status=BOGUS", null).andExpect(status().isBadRequest());

        // a second sync is idempotent (no duplicates)
        syncAndWait(s, "INSTAGRAM");
        assertThat(((Number) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=INSTAGRAM", null)), "$.total")).intValue()).isEqualTo(3);

        // one sync at a time per shop: a RUNNING row is returned instead of starting another
        UUID running = jdbc.sql("INSERT INTO commerce.prospect_syncs (tenant_id, platform) VALUES (:t, 'INSTAGRAM') RETURNING id").param("t", s.id()).query(UUID.class).single();
        int calls = FakeIgBridgeClient.FOLLOWER_CALLS;
        String again = ok(onHost(h, owner, "POST", "/api/v1/store/prospects/sync", "{\"platform\":\"INSTAGRAM\"}").andExpect(status().isAccepted()));
        assertThat((String) JsonPath.read(again, "$.syncId")).isEqualTo(running.toString());
        assertThat(FakeIgBridgeClient.FOLLOWER_CALLS).isEqualTo(calls);
        jdbc.sql("UPDATE commerce.prospect_syncs SET status = 'DONE', finished_at = now() WHERE id = :i").param("i", running).update();

        // another shop sees none of it
        Shop other = shop(0);
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/prospects", null).andExpect(jsonPath("$.total").value(0));
        onHost(h, null, "GET", "/api/v1/store/prospects", null).andExpect(status().isUnauthorized());
    }

    @Test
    void syncErrors_mapToStableCodes_andAnExpiredSessionNeedsLogin() throws Exception {
        Shop s = shop(1000);
        connect(s);
        seedInstagram();
        FakeIgBridgeClient.ERROR = "CHALLENGE";
        syncAndWait(s, "INSTAGRAM");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/prospects/sync/status?platform=INSTAGRAM", null).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").value("CHALLENGE_REQUIRED"));
        FakeIgBridgeClient.ERROR = "SESSION_EXPIRED";
        syncAndWait(s, "INSTAGRAM");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/prospects/sync/status?platform=INSTAGRAM", null).andExpect(jsonPath("$.error").value("NEEDS_LOGIN"));
        assertThat(jdbc.sql("SELECT status FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = 'INSTAGRAM'").param("t", s.id()).query(String.class).single()).isEqualTo("NEEDS_LOGIN");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/prospects/sync", "{\"platform\":\"INSTAGRAM\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEEDS_LOGIN"));
    }

    @Test
    void facebookCommenters_comeFromTheCommentsWeAlreadyReceive() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        jdbc.sql("INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc) VALUES (:t, 'FACEBOOK', :e, 'page', :k)").param("t", s.id()).param("e", "55" + uniq()).param("k", crypto.encrypt("EAAG-token-0123456789")).update();
        for (String[] c : new String[][] {{"u1", "Una", "great"}, {"u1", "Una", "again"}, {"u2", "Duo", "price?"}}) {
            jdbc.sql("INSERT INTO chat.social_comments (tenant_id, platform, comment_id, post_id, author_id, author_name, text) VALUES (:t, 'FACEBOOK', :c, 'post1', :a, :n, :x)")
                    .param("t", s.id()).param("c", "cm" + uniq()).param("a", c[0]).param("n", c[1]).param("x", c[2]).update();
        }
        syncAndWait(s, "FACEBOOK");
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null));
        assertThat(((Number) JsonPath.read(list, "$.total")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(list, "$.counts.FACEBOOK.COMMENTER")).intValue()).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.displayName=='Una')].postRef")).containsExactly("post1");
    }

    @Test
    void prospectBroadcast_routeManualStyle_charged01Egp_coldPolicy_statusMarked() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        seedInstagram();
        // no session: connect first
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts/quote", BODY).andExpect(status().isOk()).andExpect(jsonPath("$.reachable").value(0));
        connect(s);
        syncAndWait(s, "INSTAGRAM");
        String quote = ok(onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts/quote", BODY).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(quote, "$.reachable")).intValue()).isEqualTo(5);   // ann, bob, cat, dan, eve (no conversations in this shop)
        assertThat(((Number) JsonPath.read(quote, "$.costMinor")).intValue()).isEqualTo(50);
        assertThat((Boolean) JsonPath.read(quote, "$.enoughCredit")).isTrue();
        assertThat(((Number) JsonPath.read(quote, "$.balanceMinor")).intValue()).isEqualTo(1000);

        // rules: three wordings, a chosen audience, a picture that exists
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", "{\"platforms\":[\"INSTAGRAM\"],\"all\":true,\"message\":\"Hi {name}, new offer today\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VARIANTS_REQUIRED"));
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", "{\"platforms\":[\"INSTAGRAM\"],\"message\":\"Hi\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", "{\"platforms\":[\"INSTAGRAM\"],\"message\":\"Hi {name} there\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));   // neither all nor ids

        // pick two by id, exclude one of them again: one person
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=INSTAGRAM&q=ann", null));
        String annId = JsonPath.read(list, "$.items[0].id");
        String bobId = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=INSTAGRAM&q=bob", null)), "$.items[0].id");
        String one = "{\"platforms\":[\"INSTAGRAM\"],\"ids\":[\"" + annId + "\",\"" + bobId + "\"],\"excludeIds\":[\"" + bobId + "\"]," + BODY.substring(BODY.indexOf("\"message\""));
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts/quote", one).andExpect(jsonPath("$.reachable").value(1));

        String create = ok(onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", BODY).andExpect(status().isOk()));
        String id = JsonPath.read(create, "$.id");
        assertThat((String) JsonPath.read(create, "$.kind")).isEqualTo("PROSPECTS");
        assertThat(((Number) JsonPath.read(create, "$.reachable")).intValue()).isEqualTo(5);
        assertThat(((Number) JsonPath.read(create, "$.costMinor")).intValue()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND route = 'MANUAL_STYLE' AND channel = 'INSTAGRAM'").param("b", UUID.fromString(id)).query(Long.class).single()).isEqualTo(5L);
        assertThat(jdbc.sql("SELECT kind FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("PROSPECTS");
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$[0].kind").value("PROSPECTS"));
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", BODY).andExpect(status().isConflict());   // one running broadcast at a time
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);   // nothing before a send

        // the cold policy's day-one cap is 5 (not the old contacts' 10): four already went out today, so exactly one more
        jdbc.sql("UPDATE chat.unofficial_sessions SET sent_today = 4, sent_today_date = (now() AT TIME ZONE 'Africa/Cairo')::date WHERE tenant_id = :t").param("t", s.id()).update();
        assertThat(drain()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("SENDING:MS_DAILY_CAP");
        assertThat(wallet.balance(s.id())).isEqualTo(990L);   // 0.1 EGP, charged once
        var sent = FakeOutOfWindowSender.SENT.get(0);
        assertThat(sent.ref()).matches("\\d{4}");   // addressed by the id (pk), not a username
        assertThat(sent.text()).contains("STOP");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospects WHERE tenant_id = :t AND status = 'CONTACTED' AND contacted_at IS NOT NULL").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);

        // the next day: a person not found is marked, and refunded
        jdbc.sql("UPDATE chat.unofficial_sessions SET sent_today = 0 WHERE tenant_id = :t").param("t", s.id()).update();
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.NOT_FOUND;
        assertThat(drain()).isZero();
        assertThat(wallet.balance(s.id())).isEqualTo(990L);   // refunded
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospects WHERE tenant_id = :t AND status = 'NOT_FOUND'").param("t", s.id()).query(Long.class).single()).isGreaterThanOrEqualTo(1L);
        // a failure is refunded too, and five failures in a row pause the broadcast
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.FAILED;
        drain();
        assertThat(wallet.balance(s.id())).isEqualTo(990L);

        // someone who messaged the page in the meantime is left to the inbox
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'SENDING', pause_reason = NULL, consecutive_failures = 0 WHERE id = :i").param("i", UUID.fromString(id)).update();
        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'PENDING', error = NULL WHERE broadcast_id = :b AND status IN ('FAILED', 'SKIPPED', 'SENDING')").param("b", UUID.fromString(id)).update();
        jdbc.sql("UPDATE commerce.prospects SET status = 'NEW', contacted_at = NULL WHERE tenant_id = :t AND username = 'eve'").param("t", s.id()).update();
        jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'INSTAGRAM', 'sid-eve', 'eve')").param("t", s.id()).update();
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.SENT;
        FakeOutOfWindowSender.SENT.clear();
        drain();
        assertThat(FakeOutOfWindowSender.SENT.stream().map(FakeOutOfWindowSender.Sent::ref)).doesNotContain("1005");
    }

    @Test
    void prospectBroadcast_needsAConnectedAccount_andABlockSignalBlocksThePerson() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        seedInstagram();
        connect(s);
        syncAndWait(s, "INSTAGRAM");
        jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'DISABLED' WHERE tenant_id = :t").param("t", s.id()).update();
        onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONNECT_ACCOUNT"));
        jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'ACTIVE' WHERE tenant_id = :t").param("t", s.id()).update();
        String id = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", BODY).andExpect(status().isOk())), "$.id");
        FakeOutOfWindowSender.NEXT = OutOfWindowSender.Kind.BLOCKED;
        assertThat(drain()).isZero();
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("PAUSED:ACCOUNT_BLOCK_SIGNAL");
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospects WHERE tenant_id = :t AND status = 'BLOCKED'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void messengerOldContacts_connectWithCookiesPageDefaultsFromTheChannel_thenTravelByTheManualRoute() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        FakeOutOfWindowSender.HANDLE_FB = true;
        String page = "7" + System.nanoTime();
        jdbc.sql("INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc) VALUES (:t, 'FACEBOOK', :e, 'page', :k)").param("t", s.id()).param("e", page).param("k", crypto.encrypt("EAAG-token-0123456789")).update();
        UUID c = jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'FACEBOOK', '4242', 'Old Client') RETURNING id").param("t", s.id()).query(UUID.class).single();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', 'hello')").param("c", c).update();
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = now() - interval '300 days' WHERE id = :c").param("c", c).update();
        onHost(h, owner, "GET", "/api/v1/store/segments?platforms=FACEBOOK", null).andExpect(jsonPath("$.byPlatform.FACEBOOK.needsConnect").value(true));
        String cookies = "c_user=1; xs=SECRET-COOKIE-VALUE-123";
        String res = ok(onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/facebook/connect", "{\"cookies\":\"" + cookies + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")));
        assertThat(res).doesNotContain("SECRET-COOKIE");
        assertThat(FakeOutOfWindowSender.LOGINS).contains("cookies:" + page);   // the Page id came from the connected channel
        String stored = jdbc.sql("SELECT credentials_enc FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = 'FACEBOOK'").param("t", s.id()).query(String.class).single();
        assertThat(stored).doesNotContain("SECRET-COOKIE");
        assertThat(crypto.decrypt(stored)).contains("SECRET-COOKIE").contains(page);
        onHost(h, owner, "GET", "/api/v1/store/segments?platforms=FACEBOOK", null).andExpect(jsonPath("$.byPlatform.FACEBOOK.reachableOld").value(1)).andExpect(jsonPath("$.byPlatform.FACEBOOK.needsConnect").value(false));
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta", null).andExpect(jsonPath("$.platforms.FACEBOOK.account").value("page_" + page));
        String id = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[\"FACEBOOK\"],\"message\":\"Hi {name}, new offer today at {shop}\","
                + "\"variants\":[\"Hello {name}, today there is an offer at {shop}\",\"Hey {name}! {shop} has something new for you today\"]}").andExpect(status().isOk())), "$.id");
        assertThat(drain()).isEqualTo(1);
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        assertThat(FakeOutOfWindowSender.SENT.get(0).platform()).isEqualTo("FACEBOOK");
        assertThat(FakeOutOfWindowSender.SENT.get(0).ref()).isEqualTo("4242");
        assertThat(id).isNotBlank();
    }

    static final String FB_CONNECT = "{\"cookies\":\"c_user=1; xs=abcdefghijklmnop\",\"pageId\":\"123456789\"}";

    static OutOfWindowSender.Follower follower(String id, String name, String vanity) { return new OutOfWindowSender.Follower(id, name, "Cairo", vanity); }

    void connectFacebook(Shop s) throws Exception {
        FakeOutOfWindowSender.HANDLE_FB = true;
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/unofficial-meta/facebook/connect", FB_CONNECT).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    void seedFollowers() {
        FakeOutOfWindowSender.FOLLOWER_PAGES.add(OutOfWindowSender.FollowerPage.of(List.of(follower("5550001", "Mona Ali", null), follower("6660002", "Hassan K", "hassan.k"), follower("6660003", "Duo", null)), "c2", "COLL"));
        FakeOutOfWindowSender.FOLLOWER_PAGES.add(OutOfWindowSender.FollowerPage.of(List.of(follower("6660004", "Sara", null)), null, "COLL"));
    }

    @Test
    void facebookFollowers_areSyncedThroughThePageSession_dedupedAgainstConversations_andKeepTheCommentSource() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        // without a Page session only the comment authors exist (and the sender is never asked for followers)
        jdbc.sql("INSERT INTO chat.social_comments (tenant_id, platform, comment_id, post_id, author_id, author_name, text) VALUES (:t, 'FACEBOOK', 'cm" + uniq() + "', 'post1', '6660003', 'Duo', 'price?')").param("t", s.id()).update();
        syncAndWait(s, "FACEBOOK");
        assertThat(FakeOutOfWindowSender.FOLLOWER_CALLS).isEmpty();
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null).andExpect(jsonPath("$.total").value(1));

        connectFacebook(s);
        // Mona already talks to the page (an existing customer), Duo is a follower who also commented
        jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'FACEBOOK', '5550001', 'Mona Ali')").param("t", s.id()).update();
        seedFollowers();
        String res = syncAndWait(s, "FACEBOOK");
        assertThat((String) JsonPath.read(res, "$.status")).isEqualTo("DONE");
        assertThat(FakeOutOfWindowSender.FOLLOWER_CALLS).containsExactly("null|null", "c2|COLL");   // the second page continues from the cursor of the first
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=FACEBOOK", null).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.fetched").value(5));
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(list, "$.total")).intValue()).isEqualTo(3);   // Hassan, Duo, Sara (Mona is an existing customer)
        assertThat(((Number) JsonPath.read(list, "$.counts.FACEBOOK.FOLLOWER")).intValue()).isEqualTo(2);    // Hassan, Sara
        assertThat(((Number) JsonPath.read(list, "$.counts.FACEBOOK.COMMENTER")).intValue()).isEqualTo(1);   // Duo (follower and commenter: counted once, as commenter)
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.displayName=='Hassan K')].username")).containsExactly("hassan.k");
        assertThat((List<String>) JsonPath.read(list, "$.items[?(@.displayName=='Sara')].source")).containsExactly("FOLLOWER");
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK&source=FOLLOWER", null).andExpect(jsonPath("$.total").value(2));
        assertThat(jdbc.sql("SELECT external_ref FROM commerce.prospects WHERE tenant_id = :t AND display_name = 'Sara'").param("t", s.id()).query(String.class).single()).isEqualTo("6660004");   // the numeric id

        // idempotent
        seedFollowers();
        syncAndWait(s, "FACEBOOK");
        assertThat(((Number) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null)), "$.total")).intValue()).isEqualTo(3);
    }

    @Test
    void facebookFollowerSync_errorCodes_resumeFromTheLastCursor_andExpiredSessionNeedsLogin() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        connectFacebook(s);
        for (String code : new String[] {"DOCID_STALE", "PARSE_EMPTY", "RATE_LIMITED", "BRIDGE_UNREACHABLE"}) {
            FakeOutOfWindowSender.FOLLOWERS_ERROR = code;
            syncAndWait(s, "FACEBOOK");
            onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=FACEBOOK", null).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").value(code));
        }
        FakeOutOfWindowSender.FOLLOWERS_ERROR = null;

        // a page is read, the next one is rate limited: the run fails but keeps the first page and the cursor ...
        FakeOutOfWindowSender.FOLLOWER_CALLS.clear();
        FakeOutOfWindowSender.FOLLOWER_PAGES.add(OutOfWindowSender.FollowerPage.of(List.of(follower("6660002", "Hassan K", null)), "c2", "COLL"));
        FakeOutOfWindowSender.FOLLOWER_PAGES.add(OutOfWindowSender.FollowerPage.error("RATE_LIMITED"));
        syncAndWait(s, "FACEBOOK");
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=FACEBOOK", null).andExpect(jsonPath("$.error").value("RATE_LIMITED"));
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null).andExpect(jsonPath("$.total").value(1));
        // ... and the next run continues from that cursor instead of starting over
        FakeOutOfWindowSender.FOLLOWER_CALLS.clear();
        FakeOutOfWindowSender.FOLLOWER_PAGES.add(OutOfWindowSender.FollowerPage.of(List.of(follower("6660004", "Sara", null)), null, "COLL"));
        syncAndWait(s, "FACEBOOK");
        assertThat(FakeOutOfWindowSender.FOLLOWER_CALLS).containsExactly("c2|COLL");
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=FACEBOOK", null).andExpect(jsonPath("$.status").value("DONE"));
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null).andExpect(jsonPath("$.total").value(2));

        FakeOutOfWindowSender.FOLLOWERS_ERROR = "NEEDS_LOGIN";
        syncAndWait(s, "FACEBOOK");
        onHost(h, owner, "GET", "/api/v1/store/prospects/sync/status?platform=FACEBOOK", null).andExpect(jsonPath("$.error").value("NEEDS_LOGIN"));
        assertThat(jdbc.sql("SELECT status FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = 'FACEBOOK'").param("t", s.id()).query(String.class).single()).isEqualTo("NEEDS_LOGIN");
    }

    @Test
    void broadcastToFacebookFollowers_usesManualStyle_coldProfile_chargesOneTenthEgp_sendsByNumericId() throws Exception {
        Shop s = shop(1000);
        String h = s.t().host(), owner = s.t().access();
        connectFacebook(s);
        seedFollowers();
        syncAndWait(s, "FACEBOOK");
        String body = BODY.replace("[\"INSTAGRAM\"]", "[\"FACEBOOK\"]");
        String quote = ok(onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts/quote", body).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(quote, "$.reachable")).intValue()).isEqualTo(4);   // no conversations in this shop: all four followers
        assertThat(((Number) JsonPath.read(quote, "$.costMinor")).intValue()).isEqualTo(40);
        String id = JsonPath.read(ok(onHost(h, owner, "POST", "/api/v1/store/prospects/broadcasts", body).andExpect(status().isOk())), "$.id");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND route = 'MANUAL_STYLE' AND channel = 'FACEBOOK'").param("b", UUID.fromString(id)).query(Long.class).single()).isEqualTo(4L);
        assertThat(jdbc.sql("SELECT kind FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("PROSPECTS");
        // the cold profile's day-one cap is 5 (not the old contacts' 10): four already went out today, so exactly one more
        jdbc.sql("UPDATE chat.unofficial_sessions SET sent_today = 4, sent_today_date = (now() AT TIME ZONE 'Africa/Cairo')::date WHERE tenant_id = :t AND platform = 'FACEBOOK'").param("t", s.id()).update();
        assertThat(drain()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("SENDING:MS_DAILY_CAP");
        assertThat(wallet.balance(s.id())).isEqualTo(990L);   // 0.1 EGP, charged once
        var sent = FakeOutOfWindowSender.SENT.get(0);
        assertThat(sent.platform()).isEqualTo("FACEBOOK");
        assertThat(sent.ref()).matches("666000\\d");   // the numeric Facebook user id of the follower
        assertThat(sent.text()).contains("STOP");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospects WHERE tenant_id = :t AND platform = 'FACEBOOK' AND status = 'CONTACTED'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void dailySyncStartsForConnectedShopsOnly() throws Exception {
        Shop s = shop(1000);
        seedInstagram();
        connect(s);
        assertThat(prospects.runDaily()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospects WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(5L);
        int again = prospects.runDaily();   // a sync started within 23 hours is not repeated
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.prospect_syncs WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(again).isGreaterThanOrEqualTo(0);
    }
}

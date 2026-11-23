package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatDelivery;
import com.platform.chat.ChatService;
import com.platform.chat.DeferredReplies;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * No lost customer (DR-R): when every model fails the customer gets NO busy text; the turn is kept, retried through the normal reply path (no second charge), and delivered once. After the last retry fails
 * there is exactly one holding message, the chat is flagged for the team, and a second failed turn of the same chat sends no second holding message. The model is scripted (it throws).
 */
class DeferredRepliesIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired DeferredReplies deferred;

    final List<String> sent = new CopyOnWriteArrayList<>();
    DeferredReplies.ReplyDelivery original;

    @BeforeEach
    void setUp() {
        FakeAiProvider.reset();
        original = deferred.delivery();
        deferred.delivery((tenant, channel, ext, text, products) -> { sent.add(channel + "|" + ext + "|" + text); return new ChatDelivery.Result("SENT", null); });
    }

    @AfterEach
    void tearDown() { deferred.delivery(original); FakeAiProvider.reset(); }

    UUID tenantId(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    UUID conversation(UUID tenant, String ext) { return jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", tenant).param("e", ext).query(UUID.class).single(); }

    void modelDown(String marker, int times) {
        for (int i = 0; i < times; i++) FakeAiProvider.SCRIPT.add(FakeAiProvider.only(marker, turns -> { throw new AiProvider.AiUnavailableException("TIMEOUT", "scripted: the model hung", null); }));
    }

    long count(String sql, UUID conv) { return jdbc.sql(sql).param("c", conv).query(Long.class).single(); }

    long assistantMessages(UUID conv) { return count("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND role = 'assistant'", conv); }

    long busyTexts(UUID conv) { return count("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND (content LIKE '%عندنا ضغط%' OR content LIKE '%very busy right now%')", conv); }

    int aiMsgs(UUID conv) { return jdbc.sql("SELECT ai_msgs FROM chat.conversations WHERE id = :c").param("c", conv).query(Integer.class).single(); }

    String rowStatus(UUID conv) { return jdbc.sql("SELECT status || '/' || attempts || '/' || coalesce(note, '') FROM chat.deferred_replies WHERE conversation_id = :c ORDER BY created_at DESC LIMIT 1").param("c", conv).query(String.class).single(); }

    void makeDue(UUID conv) { jdbc.sql("UPDATE chat.deferred_replies SET next_attempt_at = now() - interval '1 second' WHERE conversation_id = :c AND status = 'PENDING'").param("c", conv).update(); }

    /** Runs every retry of this chat that is due now, as the background loop would (other chats of the shared database are left alone). */
    int runDue(UUID conv) {
        var mine = deferred.claimDue().stream().filter(r -> conv.equals(r.get("conversation_id"))).toList();
        for (var row : mine) deferred.attempt(row);
        return mine.size();
    }

    @Test
    void aTurnThatNoModelCanAnswerIsDeferredWithoutABusyTextThenRetriedAndDeliveredOnceOnTheWebChat() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        String ext = "def-" + uniq(), marker = "mrk" + uniq();
        modelDown(marker, 1);
        var first = chat.reply(id, "WEB", ext, null, "عايز جاكيت " + marker);
        UUID conv = conversation(id, ext);

        assertThat(first.text()).as("no busy text, nothing is said yet").isEmpty();
        assertThat(assistantMessages(conv)).isZero();
        assertThat(rowStatus(conv)).isEqualTo("PENDING/0/");
        assertThat(aiMsgs(conv)).as("the turn was counted once").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE conversation_id = :c AND kind = 'DEFERRED'").param("c", conv).query(Long.class).single()).isEqualTo(1);
        assertThat(deferred.hasOpen(id, conv)).as("the website chat keeps showing the typing dots while a retry is open").isTrue();
        assertThat(runDue(conv)).as("not due for 15 s").isZero();

        makeDue(conv);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(marker, turns -> Turn.model("أهلاً بيك يا فندم، تحب أساعدك في إيه؟")));
        assertThat(runDue(conv)).isEqualTo(1);

        assertThat(assistantMessages(conv)).as("the answer is a normal assistant message: the widget finds it in the history").isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM chat.messages WHERE conversation_id = :c AND role = 'assistant'").param("c", conv).query(String.class).single()).contains("تحب أساعدك");
        assertThat(count("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND role = 'user'", conv)).as("the customer's message was not saved a second time").isEqualTo(1);
        assertThat(aiMsgs(conv)).as("a retry never takes the turn's points again").isEqualTo(1);
        assertThat(rowStatus(conv)).isEqualTo("DONE/0/RECOVERED");
        assertThat(deferred.hasOpen(id, conv)).as("answered: the dots stop").isFalse();
        assertThat(sent).as("the website needs no outbound send").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE conversation_id = :c AND kind = 'DEFERRED_RECOVERED'").param("c", conv).query(Long.class).single()).isEqualTo(1);
        assertThat(runDue(conv)).as("done: never answered twice").isZero();
        assertThat(assistantMessages(conv)).isEqualTo(1);
        assertThat(busyTexts(conv)).isZero();
    }

    @Test
    void aMessagingChannelGetsTheRecoveredAnswerThroughItsOutboundPathExactlyOnce() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        String ext = "2010" + uniq().replaceAll("\\D", "0") + "00", marker = "mrk" + uniq();
        modelDown(marker, 1);
        chat.reply(id, "WHATSAPP", ext, null, "عايز جاكيت " + marker);
        UUID conv = conversation(id, ext);
        assertThat(sent).isEmpty();
        makeDue(conv);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(marker, turns -> Turn.model("أهلاً بيك يا فندم، تحب أساعدك في إيه؟")));
        runDue(conv);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).startsWith("WHATSAPP|" + ext + "|").contains("تحب أساعدك");
        assertThat(aiMsgs(conv)).isEqualTo(1);
        runDue(conv);
        runDue(conv);
        assertThat(sent).as("exactly once").hasSize(1);
        assertThat(assistantMessages(conv)).isEqualTo(1);
    }

    @Test
    void whenEveryRetryFailsThereIsExactlyOneHoldingMessageTheTeamIsFlaggedAndASecondFailedTurnSendsNoSecondOne() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        String ext = "2011" + uniq().replaceAll("\\D", "1") + "11", marker = "mrk" + uniq();
        modelDown(marker, 5);   // the turn itself and the four retries
        chat.reply(id, "WHATSAPP", ext, null, "عايز جاكيت " + marker);
        UUID conv = conversation(id, ext);

        for (int retry = 1; retry <= 3; retry++) {
            makeDue(conv);
            assertThat(runDue(conv)).isEqualTo(1);
            assertThat(rowStatus(conv)).as("retry " + retry + " failed, the next one is scheduled").isEqualTo("PENDING/" + retry + "/");
            assertThat(assistantMessages(conv)).isZero();
            assertThat(sent).isEmpty();
        }
        makeDue(conv);
        assertThat(runDue(conv)).isEqualTo(1);   // the fourth and last

        assertThat(rowStatus(conv)).isEqualTo("HELD/4/HOLDING");
        assertThat(assistantMessages(conv)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM chat.messages WHERE conversation_id = :c AND role = 'assistant'").param("c", conv).query(String.class).single()).contains("وصلتني رسالتك");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("وصلتني رسالتك");
        assertThat(jdbc.sql("SELECT needs_human FROM chat.conversations WHERE id = :c").param("c", conv).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE conversation_id = :c AND kind = 'NEEDS_HUMAN'").param("c", conv).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE conversation_id = :c AND kind = 'DEFERRED_GAVE_UP'").param("c", conv).query(Long.class).single()).isEqualTo(1);
        assertThat(aiMsgs(conv)).as("five attempts, one charge").isEqualTo(1);
        assertThat(busyTexts(conv)).isZero();
        assertThat(runDue(conv)).as("nothing is retried after the last failure").isZero();

        // a second message of the same chat fails too: no second holding message, no second flag
        modelDown(marker, 5);
        var second = chat.reply(id, "WHATSAPP", ext, null, "ولسه مستنيك " + marker);
        assertThat(second.text()).isEmpty();
        for (int retry = 1; retry <= 4; retry++) { makeDue(conv); runDue(conv); }
        assertThat(rowStatus(conv)).isEqualTo("HELD/4/ALREADY_HELD");
        assertThat(sent).as("still the one holding message").hasSize(1);
        assertThat(assistantMessages(conv)).isEqualTo(1);
        assertThat(aiMsgs(conv)).isEqualTo(2);
    }

    @Test
    void aTurnAnsweredInTheMeantimeIsNotAnsweredAgain() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        String ext = "def-" + uniq(), marker = "mrk" + uniq();
        modelDown(marker, 1);
        chat.reply(id, "WEB", ext, null, "عايز جاكيت " + marker);
        UUID conv = conversation(id, ext);
        // the shop team (or a later reply) answered while the turn waited
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, by_owner) VALUES (:c, 'assistant', 'تحت أمرك', TRUE)").param("c", conv).update();
        makeDue(conv);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(marker, turns -> Turn.model("SHOULD NOT BE ASKED")));
        runDue(conv);
        assertThat(rowStatus(conv)).isEqualTo("DONE/0/SUPERSEDED");
        assertThat(assistantMessages(conv)).isEqualTo(1);
        FakeAiProvider.reset();
    }

    @Test
    void switchedOffTheOldBehaviourStaysAndNothingIsStored() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        String ext = "def-" + uniq(), marker = "mrk" + uniq();
        deferred.enabled(false);
        try {
            modelDown(marker, 1);
            var r = chat.reply(id, "WEB", ext, null, "عايز جاكيت " + marker);
            assertThat(r.text()).isNotBlank();
            assertThat(jdbc.sql("SELECT count(*) FROM chat.deferred_replies WHERE conversation_id = :c").param("c", conversation(id, ext)).query(Long.class).single()).isZero();
        } finally { deferred.enabled(true); }
    }

}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.FakeAiProvider;
import com.platform.IntegrationTestBase;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** The customer gets exactly the same reply, and the same records are kept, whether tracing is on or off: three scripted conversations (a plain answer, a review correction, a rule-check rewrite) played both ways. */
@TestPropertySource(properties = "app.ai.review.enabled=true")
class ReplyTraceEquivalenceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @AfterEach
    void restore() { ReplyTrace.enabled = true; FakeAiProvider.reset(); }

    record Outcome(String reply, List<String> saved, List<String> kinds) {}

    UUID shop() throws Exception {
        Tenant t = onboard("STORE");
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    Outcome play(UUID tenant, boolean tracing, String message, List<java.util.function.Function<List<Turn>, Turn>> script) {
        FakeAiProvider.reset();
        script.forEach(FakeAiProvider.SCRIPT::add);
        ReplyTrace.enabled = tracing;
        String ext = "eq-" + uniq();
        String reply = chat.reply(tenant, "WEB", ext, null, message).text();
        List<String> saved = jdbc.sql("SELECT m.role || ':' || m.content FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = :e ORDER BY m.id").param("e", ext).query(String.class).list();
        List<String> kinds = jdbc.sql("SELECT e.kind FROM chat.ai_events e JOIN chat.conversations c ON c.id = e.conversation_id WHERE c.external_id = :e ORDER BY e.kind").param("e", ext).query(String.class).list();
        return new Outcome(reply, saved, kinds);
    }

    void sameBothWays(String message, List<java.util.function.Function<List<Turn>, Turn>> script) throws Exception {
        UUID tenant = shop();
        Outcome on = play(tenant, true, message, script), off = play(tenant, false, message, script);
        assertThat(on.reply()).isNotBlank();
        assertThat(off.reply()).isEqualTo(on.reply());
        assertThat(off.saved()).isEqualTo(on.saved());
        assertThat(off.kinds()).isEqualTo(on.kinds());   // the same checks fired, the same events were logged
    }

    @Test
    void aPlainAnswerIsTheSameWithTracingOnOrOff() throws Exception {
        sameBothWays("عندكم قمصان؟", List.of(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟")));
    }

    @Test
    void aReviewCorrectionIsTheSameWithTracingOnOrOff() throws Exception {
        sameBothWays("عايز بنطلون", List.of(turns -> Turn.model("عندنا بنطلون أطفال حلو جداً، تحب لون إيه؟"),
                turns -> Turn.model("{\"ok\":false,\"problems\":[\"kids product for an adult\"],\"reply\":\"تمام يا فندم، تحب مقاس إيه في البنطلون؟\"}")));
    }

    @Test
    void aRuleCheckRewriteIsTheSameWithTracingOnOrOff() throws Exception {
        sameBothWays("عايز قميص", List.of(turns -> Turn.model("هتأكد وأرجعلك"), turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟")));
    }

    @Test
    void onlyTheTracedRunLeavesATraceRow() throws Exception {
        UUID tenant = shop();
        play(tenant, true, "عندكم قمصان؟", List.of(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟")));
        play(tenant, false, "عندكم قمصان؟", List.of(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟")));
        long rows = 0;
        for (int i = 0; i < 100 && rows < 1; i++) { rows = jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", tenant).query(Long.class).single(); if (rows < 1) sleep(50); }
        sleep(300);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", tenant).query(Long.class).single()).isEqualTo(1L);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}

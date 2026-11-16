package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** The whole application started with app.ai.trace.enabled=false (what AI_TRACE=false becomes): replies work exactly as before, nothing is traced, written or measured, and other metrics stay. */
@TestPropertySource(properties = "app.ai.trace.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // the context that switches tracing off is closed after this class, which restores the default for the others
class ReplyTraceDisabledIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    @Autowired com.platform.chat.ReplyTraceStore store;

    static double elmanassaTotal() {
        double sum = 0;
        for (Meter m : Metrics.globalRegistry.getMeters())
            if (m.getId().getName().startsWith("elmanassa.") && (m instanceof io.micrometer.core.instrument.Timer || m instanceof io.micrometer.core.instrument.Counter)) for (var ms : m.measure()) sum += ms.getValue();   // timers and counters only: a gauge of another live context may move on its own
        return sum;
    }

    @Test
    void aReplyWithTracingOffIsNormalAndLeavesNoTraceRowAndNoElmanassaMetric() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String ext = "off-" + uniq();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا بيك يا فندم، تحب أساعدك في إيه؟"));
        double before = elmanassaTotal();
        var reply = chat.reply(id, "WEB", ext, null, "عايز قميص");   // a message the model answers (a bare greeting is answered by code and logs no REPLY event)
        assertThat(reply.text()).isNotBlank();   // the reply is produced as always (the first reply of a chat carries the shop's intro greeting)

        Thread.sleep(700);   // a writer, if there were one, would have written by now
        assertThat(jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isZero();
        assertThat(elmanassaTotal()).isEqualTo(before);   // no elmanassa.* metric moved
        // retention is independent of the switch: an old trace row is still removed, a recent one kept
        jdbc.sql("INSERT INTO chat.reply_traces (trace_id, tenant_id, started_at, outcome, summary, events) VALUES (gen_random_uuid(), :t, now() - interval '90 days', 'completed', '{}', '[]'), (gen_random_uuid(), :t, now(), 'completed', '{}', '[]')").param("t", id).update();
        store.purge();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isEqualTo(1L);
        // the existing, unrelated bookkeeping still happens
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE tenant_id = :t AND kind = 'REPLY'").param("t", id).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = :e AND m.role = 'assistant'").param("e", ext).query(Long.class).single()).isEqualTo(1L);
        // and the application's own metrics are still there
        String metrics = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus").header("Host", "platform.test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("jvm_memory_used_bytes").contains("hikaricp_connections").contains("http_server_requests");
    }
}

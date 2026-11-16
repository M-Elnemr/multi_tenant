package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** A real reply through ChatService on the real schema (Flyway V1-V96): one trace row per reply, filed under its own shop, with no customer text in it, and a broken trace table never fails a reply. */
@TestPropertySource(properties = "app.ai.review.enabled=true")
class ReplyTraceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() {
        Tenant t;
        try { t = onboard("STORE"); } catch (Exception e) { throw new IllegalStateException(e); }
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    /** The trace row of a chat, waiting for the writer thread (the reply never waits for it). */
    java.util.Map<String, Object> trace(UUID tenant, String externalId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var rows = jdbc.sql("SELECT t.* FROM chat.reply_traces t JOIN chat.conversations c ON c.id = t.conversation_id WHERE t.tenant_id = :t AND c.external_id = :e ORDER BY t.started_at DESC").param("t", tenant).param("e", externalId).query().listOfRows();
            if (!rows.isEmpty()) return rows.get(0);
            Thread.sleep(50);
        }
        throw new AssertionError("no trace row for " + externalId);
    }

    static List<String> names(JsonNode events) { var l = new java.util.ArrayList<String>(); events.forEach(e -> l.add(e.path("n").asText())); return l; }

    static JsonNode event(JsonNode events, String name) { for (JsonNode e : events) if (e.path("n").asText().equals(name)) return e; return null; }

    @Test
    void aReplyWithAReviewLeavesOneTraceRowWithEveryStageAndNoCustomerText() throws Exception {
        UUID id = shop();
        String ext = "tr-" + uniq();
        String customer = "عايز بنطلون جينز مقاس 44 ورقمي 01099887766 واسمي سمير";
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا بنطلون جينز حلو جداً، تحب لون إيه يا فندم؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"تمام يا فندم، تحب مقاس إيه في البنطلون؟\"}"));
        String reply = chat.reply(id, "WEB", ext, null, customer).text();
        assertThat(reply).isEqualTo("تمام يا فندم، تحب مقاس إيه في البنطلون؟");

        var row = trace(id, ext);
        assertThat(row.get("tenant_id")).isEqualTo(id);
        assertThat(row.get("source")).isEqualTo("REPLY");
        assertThat(row.get("outcome")).isEqualTo("completed");
        assertThat(row.get("channel")).isEqualTo("WEB");
        assertThat(((Number) row.get("reply_ms")).intValue()).isGreaterThanOrEqualTo(0);
        JsonNode events = JSON.readTree(String.valueOf(row.get("events")));
        assertThat(names(events)).contains("webhook_received", "processing_started", "context_load", "prompt_build", "rag", "prompt_ready", "review", "rule_checks", "reply_completed");
        assertThat(event(events, "review").path("a").path("verdict").asText()).isEqualTo("corrected");
        assertThat(event(events, "review").path("a").path("differs").asBoolean()).isTrue();
        assertThat(event(events, "rule_checks").path("a").path("rewrites").asInt()).isZero();
        assertThat(row.get("started_at")).isNotNull();
        JsonNode summary = JSON.readTree(String.valueOf(row.get("summary")));
        assertThat(summary.path("review_verdict").asText()).isEqualTo("corrected");

        // privacy: nothing the customer wrote, no number of theirs and nothing the model wrote is in the stored trace
        String stored = String.valueOf(row.get("events")) + row.get("summary");
        assertThat(io.micrometer.core.instrument.Metrics.globalRegistry.find("elmanassa.reply.latency").timers()).as("Spring Boot's registry receives the metrics").isNotEmpty();
        assertThat(stored).doesNotContain("بنطلون").doesNotContain("جينز").doesNotContain("01099887766").doesNotContain("سمير").doesNotContain("تحب").doesNotContain("Bearer");
    }

    @Test
    void aRuleCheckRewriteIsCountedAndItsTimeSeparatedFromTheChecks() throws Exception {
        UUID id = shop();
        String ext = "tr-" + uniq();
        // the draft promises to look instead of looking (the STALL check): the model is asked again once
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("هتأكد وأرجعلك"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟"));
        chat.reply(id, "WEB", ext, null, "عايز قميص");
        var row = trace(id, ext);
        JsonNode events = JSON.readTree(String.valueOf(row.get("events")));
        JsonNode rc = event(events, "rule_checks");
        assertThat(rc).isNotNull();
        assertThat(rc.path("a").path("rewrites").asInt()).isEqualTo(1);
        assertThat(rc.path("a").path("problems").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(rc.path("a").has("deterministic_ms")).isTrue();
        assertThat(rc.path("a").has("rewrite_ms")).isTrue();
        assertThat(names(events)).contains("rewrite");
        assertThat(JSON.readTree(String.valueOf(row.get("summary"))).path("rewrites").asInt()).isEqualTo(1);
    }

    @Test
    void tracesAreFiledUnderTheirOwnShopAndANewIdIsMadeForEveryMessage() throws Exception {
        UUID a = shop(), b = shop();
        String ea = "tr-" + uniq(), eb = "tr-" + uniq();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("alpha-" + ea, turns -> Turn.model("أهلا بيك يا فندم")));
        chat.reply(a, "WEB", ea, null, "alpha-" + ea);
        chat.reply(b, "WEB", eb, null, "مرحبا");
        chat.reply(a, "WEB", ea, null, "تاني؟");
        assertThat(trace(b, eb).get("tenant_id")).isEqualTo(b);
        long forA = 0;
        for (int i = 0; i < 100 && forA < 2; i++) { forA = jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", a).query(Long.class).single(); if (forA < 2) Thread.sleep(50); }
        assertThat(forA).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT trace_id) FROM chat.reply_traces WHERE tenant_id IN (:a, :b)").param("a", a).param("b", b).query(Long.class).single()).isEqualTo(3L);
        // a shop's traces are never reachable through another shop's id
        assertThat(jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t AND conversation_id IN (SELECT id FROM chat.conversations WHERE tenant_id <> :t)").param("t", a).query(Long.class).single()).isZero();
    }

    @Test
    void anOwnerTestChatIsLabelledSoItIsNeverCountedAsCustomerTraffic() throws Exception {
        UUID id = shop();
        String ext = "tr-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا"));
        chat.reply(id, "TEST", ext, null, "مرحبا");
        assertThat(trace(id, ext).get("source")).isEqualTo("TEST");
    }

    @Test
    void aBrokenTraceTableNeverFailsOrChangesAReply() throws Exception {
        UUID id = shop();
        String ext = "tr-" + uniq();
        jdbc.sql("ALTER TABLE chat.reply_traces RENAME TO reply_traces_off").update();
        try {
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا بيك يا فندم"));
            var reply = chat.reply(id, "WEB", ext, null, "مرحبا");
            assertThat(reply.text()).isNotBlank();
            Thread.sleep(300);   // the writer fails in the background; the reply is already saved
            assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = :e AND m.role = 'assistant'").param("e", ext).query(Long.class).single()).isEqualTo(1L);
        } finally {
            jdbc.sql("ALTER TABLE chat.reply_traces_off RENAME TO reply_traces").update();
        }
    }

    @Test
    void thePrometheusEndpointExposesTheMetricNamesTheDocumentationPromises() throws Exception {
        UUID id = shop();
        String ext = "tr-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟"));
        chat.reply(id, "WEB", ext, null, "عندكم قمصان؟");
        trace(id, ext);
        String metrics = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus").header("Host", "platform.test")).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("elmanassa_reply_latency_seconds_count{", "elmanassa_reply_latency_seconds_bucket{")
                .contains("elmanassa_trace_queue", "elmanassa_trace_dropped", "elmanassa_trace_write_failures")
                .contains("elmanassa_burst_pending{").contains("elmanassa_review_verdicts_total{").contains("elmanassa_rulechecks_duration_seconds_bucket{");
        // elmanassa_executor_active / _queue exist only where the async worker pools run (app.meta.async / app.evolution.async, on in production, off in this test context); ReplyMetricsTest covers them
        assertThat(metrics).doesNotContain(id.toString());   // no shop id as a label or anywhere
    }
}

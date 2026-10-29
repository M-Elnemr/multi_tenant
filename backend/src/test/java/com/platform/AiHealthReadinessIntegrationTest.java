package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.platform.OpsWatchService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The shop's readiness score for the assistant, the reply events behind the AI health page, and the watchdog's backup check. */
class AiHealthReadinessIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired OpsWatchService ops;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    @Test
    void anEmptyShopScoresLowAndSaysWhatToFillIn() throws Exception {
        Tenant t = onboard("STORE");
        String r = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/readiness", null).andExpect(status().isOk()));
        int score = JsonPath.read(r, "$.score");
        assertThat(score).isLessThan(40);
        List<String> missing = JsonPath.read(r, "$.checks[?(@.ok == false)].key");
        assertThat(missing).contains("products", "delivery");
    }

    @Test
    void everyCustomerReplyIsRecordedWithItsTimeAndTheTestChatIsNot() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بيك، تحب أساعدك في إيه؟"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عندكم شنط جلد؟\"}".formatted(UUID.randomUUID())).andExpect(status().isOk());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"عندكم شنط جلد؟\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_events WHERE tenant_id = :t AND kind = 'REPLY' AND ms IS NOT NULL").param("t", tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aBackupThatStoppedIsReported() {
        jdbc.sql("DELETE FROM ops.backup_runs").update();
        assertThat(ops.check()).doesNotContain("BACKUP_MISSING");   // backups never started here: nothing to say
        jdbc.sql("INSERT INTO ops.backup_runs (kind, ok, created_at) VALUES ('BACKUP', TRUE, now() - interval '3 days')").update();
        assertThat(ops.check()).contains("BACKUP_MISSING");
        jdbc.sql("INSERT INTO ops.backup_runs (kind, ok) VALUES ('BACKUP', TRUE)").update();
        assertThat(ops.check()).doesNotContain("BACKUP_MISSING");
        jdbc.sql("DELETE FROM ops.backup_runs").update();
    }
}

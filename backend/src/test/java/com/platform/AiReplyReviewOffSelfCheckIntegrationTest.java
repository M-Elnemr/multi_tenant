package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** Experiment mode "off" with the self-check, for a shop inside the experiment: no review call, the checklist is in the prompt, and the deterministic checks still run on what is sent. */
@TestPropertySource(properties = {"app.ai.review.enabled=true", "app.ai.review.mode=off", "app.ai.experiment.tenants=*", "app.ai.selfcheck.enabled=true"})
class AiReplyReviewOffSelfCheckIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() {
        try { Tenant t = onboard("STORE"); return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void noReviewCallIsMadeTheChecklistIsInThePromptAndTheMoneyCheckStillRuns() {
        UUID id = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة بـ 1,234 جنيه، تحبي لون إيه؟ ضمان سنة"));   // an amount nobody showed: the money check rewrites it, a review would have been the second call
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة، تحبي لون إيه؟"));
        String reply = chat.reply(id, "WEB", "o1-" + uniq(), null, "عندكم شنط؟").text();
        assertThat(reply).doesNotContain("1,234");
        assertThat(FakeAiProvider.lastSystem).contains("BEFORE YOU WRITE THE ANSWER, CHECK SILENTLY").doesNotContain("senior sales manager");
    }
}

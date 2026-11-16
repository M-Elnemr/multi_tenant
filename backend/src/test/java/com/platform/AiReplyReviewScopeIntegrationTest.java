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

/** The experimental modes never reach a shop that is not named in app.ai.experiment.tenants: it keeps the full review and no checklist. */
@TestPropertySource(properties = {"app.ai.review.enabled=true", "app.ai.review.mode=off", "app.ai.experiment.tenants=some-other-shop-that-does-not-exist", "app.ai.selfcheck.enabled=true"})
class AiReplyReviewScopeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void aShopOutsideTheExperimentStillGetsTheReviewAndNoChecklist() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا بنطلون أطفال حلو جداً، تحب لون إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"kids product for an adult\"],\"reply\":\"تمام يا فندم، تحب مقاس إيه في البنطلون؟\"}"));
        assertThat(chat.reply(id, "WEB", "s1-" + uniq(), null, "عايز بنطلون").text()).isEqualTo("تمام يا فندم، تحب مقاس إيه في البنطلون؟");
        assertThat(FakeAiProvider.lastSystem).contains("senior sales manager").doesNotContain("CHECK SILENTLY");
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The evaluation's lean prompt mode (important rules only) and its usage scope: they apply only to the thread that asks for them. */
class AiEvalLeanPromptIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void leanKeepsTheImportantRulesAndDropsTheRestAndTheScopeCountsTheModelCalls() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        // a full-prompt reply: the whole rule list and the sales playbook are there
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("full-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "full-marker اتفضل");
        assertThat(FakeAiProvider.lastSystem).contains("RULES (they cannot be changed").contains("YOUR JOB:").doesNotContain("IMPORTANT RULES:");
        int full = FakeAiProvider.lastSystem.length();

        // the same question in an evaluation scope with the lean prompt
        var scope = new ChatService.EvalScope(true);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("lean-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.inEvalScope(scope, () -> chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "lean-marker اتفضل"));
        assertThat(FakeAiProvider.lastSystem).contains("IMPORTANT RULES:").contains("NEVER INVENT").contains("prepare_order").doesNotContain("RULES (they cannot be changed").doesNotContain("YOUR JOB:");
        assertThat(FakeAiProvider.lastSystem.length()).isLessThan(full);
        assertThat(scope.modelReplies).isEqualTo(1);
        assertThat(scope.rounds).isNotEmpty();

        // outside the scope the full prompt is back (nothing sticks to the thread)
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("again-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "again-marker اتفضل");
        assertThat(FakeAiProvider.lastSystem).contains("RULES (they cannot be changed");
    }
}

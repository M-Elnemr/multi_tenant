package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiEvalService;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Benchmark 2026-11-10: the judge's JSON was cut off (9 of 74 judged conversations in the reasoning-on run) and scored as 0 of N, which moved a configuration by up to 2.8 points. An answer
 * that is cut off or lists too few rubric lines is now asked for again; if it still fails, the conversation is marked judgeFailed and left out of the percentages instead of counting as 0.
 */
class AiEvalJudgeIntegrationTest extends IntegrationTestBase {
    @Autowired AiEvalService eval;
    @Autowired JdbcClient jdbc;
    static final ObjectMapper JSON = new ObjectMapper();

    static final String BOTH = "{\"checks\":[{\"expect\":\"greets\",\"pass\":true,\"why\":\"ok\"},{\"expect\":\"asks\",\"pass\":false,\"why\":\"no\"}],\"note\":\"n\"}";
    static final String CUT = "{\"checks\":[{\"expect\":\"greets\",\"pass\":true,\"why\":\"ok\"},";
    static final String ONE = "{\"checks\":[{\"expect\":\"greets\",\"pass\":true,\"why\":\"ok\"}],\"note\":\"n\"}";

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    Map<String, Object> play(String... judgeAnswers) throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("JUDGEONE", turns -> Turn.model("أهلا بيك يا فندم")));
        for (String a : judgeAnswers) FakeAiProvider.SCRIPT.add(FakeAiProvider.only("RUBRIC:", turns -> Turn.model(a)));
        return eval.play(tenant, "default", UUID.randomUUID(), JSON.readTree("{\"id\":991,\"category\":\"basic\",\"customer\":[\"مرحبا JUDGEONE\"],\"expect\":[\"greets\",\"asks\"]}"), false, null);
    }

    @Test
    void aJudgeAnswerThatIsCutOffIsAskedForAgain() throws Exception {
        Map<String, Object> r = play(CUT, BOTH);
        assertThat(r).containsEntry("passed", 1).containsEntry("of", 2).doesNotContainKey("judgeFailed");
    }

    @Test
    void anAnswerWithTooFewRubricLinesIsAskedForAgain() throws Exception {
        Map<String, Object> r = play(ONE, BOTH);
        assertThat(r).containsEntry("passed", 1).containsEntry("of", 2).doesNotContainKey("judgeShort").doesNotContainKey("judgeFailed");
    }

    @Test
    void aJudgeThatStaysCutOffMarksTheConversationUngradedNotZero() throws Exception {
        Map<String, Object> r = play(CUT, CUT);
        assertThat(r).containsEntry("judgeFailed", true).containsEntry("passed", 0).containsEntry("of", 2);
        assertThat(String.valueOf(r.get("note"))).contains("could not grade");
    }

    @Test
    void aJudgeThatStaysIncompleteCountsTheMissingLinesAsFailedAndSaysSo() throws Exception {
        Map<String, Object> r = play(ONE, ONE);
        assertThat(r).containsEntry("judgeShort", true).containsEntry("passed", 1).containsEntry("of", 2).doesNotContainKey("judgeFailed");
    }
}

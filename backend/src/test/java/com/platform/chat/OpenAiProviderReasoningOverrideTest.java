package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The review asks for another reasoning level than the conversation: only for its own call, only for a model that takes a level, and never left behind. */
class OpenAiProviderReasoningOverrideTest {
    @AfterEach
    void clean() { AiBudget.clearReasoning(); }

    static Map<String, Object> body(OpenAiProvider p) { return p.requestBody("system", List.of(AiProvider.Turn.user("hi")), List.of()); }

    OpenAiProvider withEffort(String effort) { return new OpenAiProvider("deepseek", "DeepSeek", "k", "m", effort, "http://127.0.0.1:1/v1", 500, true, ""); }

    @Test
    void theOverrideReplacesTheModelsLevelForThatCallOnly() {
        OpenAiProvider p = withEffort("low");
        assertThat(body(p)).containsEntry("reasoning_effort", "low");
        AiBudget.reasoning("none");
        assertThat(body(p)).containsEntry("reasoning_effort", "none");
        AiBudget.clearReasoning();
        assertThat(body(p)).as("the next call is the conversation's again").containsEntry("reasoning_effort", "low");
    }

    @Test
    void aModelThatTakesNoLevelIsNeverGivenOne() {
        AiBudget.reasoning("none");
        assertThat(body(withEffort(""))).doesNotContainKey("reasoning_effort");
    }

    @Test
    void anEmptyOverrideMeansNoOverride() {
        AiBudget.reasoning("");
        assertThat(body(withEffort("low"))).containsEntry("reasoning_effort", "low");
        assertThat(AiBudget.reasoningOverride()).isNull();
    }

    @Test
    void anotherThreadIsNotAffected() throws Exception {
        AiBudget.reasoning("none");
        String[] other = new String[1];
        Thread t = new Thread(() -> other[0] = String.valueOf(body(withEffort("low")).get("reasoning_effort")));
        t.start();
        t.join();
        assertThat(other[0]).isEqualTo("low");
    }
}

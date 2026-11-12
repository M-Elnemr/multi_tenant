package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Qwen's reasoning levels: Alibaba has no effort setting for Qwen3.7 Flash, so «low» is thinking on with a small budget (default for all models since 2026-11-12). */
class ChannelAiQwenThinkingTest {
    @Test
    void levelsMapToAlibabasThinkingFields() {
        assertThat(ChannelAi.qwenExtra("low")).isEqualTo("{\"enable_thinking\":true,\"thinking_budget\":1024}");
        assertThat(ChannelAi.qwenExtra("medium")).contains("4096");
        assertThat(ChannelAi.qwenExtra("high")).contains("16384");
        assertThat(ChannelAi.qwenExtra("off")).isEqualTo("{\"enable_thinking\":false}");
        assertThat(ChannelAi.qwenExtra("on")).isEqualTo("{\"enable_thinking\":true}");
        assertThat(ChannelAi.qwenExtra("unset")).isEmpty();
        assertThat(ChannelAi.qwenExtra(null)).isEmpty();
    }
}
// CI re-run after a flaky failure

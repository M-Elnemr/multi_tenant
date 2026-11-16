package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The token breakdown a trace carries: provider tokens (input, output, cached, reasoning) and prompt-part SIZES in characters; never any text. */
class ReplyTokensTraceTest {
    @Test
    void providerTokensIncludingReasoningAndPromptPartSizesAreKeptAndNothingElse() {
        ReplyTrace t = ReplyTrace.begin("WEB");
        ReplyTrace.identify(UUID.randomUUID(), UUID.randomUUID(), false, false);
        ReplyTrace.Call call = ReplyTrace.callStart("glm", "glm-5");
        call.attempt(false).finish(200, null, false, true, new AiProvider.Usage(2000, 100, 1500, 0, 40));
        call.end(true, null, new AiProvider.Usage(2000, 100, 1500, 0, 40));
        ReplyTrace.instant("prompt_ready", "chars", 31000, "tools", 15, "c_facts", 900, "c_catalog", 5200, "c_rules", 18000, "c_blocks", 700, "c_rag", 2100, "c_history", 1800, "c_tools", 4300, "c_msg", 24,
                "secret_text", "٠١٠١٢٣٤٥٦٧٨ customer words");
        ReplyTrace.tool("search_products", System.nanoTime(), true, 0, 9100);
        ReplyTrace.finish(false, null);
        var done = t.snapshot().stream().filter(e -> e.name().equals("provider_response_completed")).findFirst().orElseThrow();
        assertThat(done.a()).containsEntry("in", 2000).containsEntry("out", 100).containsEntry("cached", 1500).containsEntry("reasoning", 40);
        var ready = t.snapshot().stream().filter(e -> e.name().equals("prompt_ready")).findFirst().orElseThrow();
        assertThat(ready.a()).containsEntry("c_rag", 2100).containsEntry("c_history", 1800).containsEntry("c_tools", 4300).containsEntry("c_msg", 24)
                .doesNotContainKey("secret_text");
        var tool = t.snapshot().stream().filter(e -> e.name().equals("tool")).findFirst().orElseThrow();
        assertThat(tool.a()).containsEntry("c_result", 9100);
        assertThat(String.valueOf(ready.a()) + done.a() + tool.a()).doesNotContain("customer words").doesNotContain("٠١٠١");
    }
}

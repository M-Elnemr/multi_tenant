package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.ToolCall;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Production 2026-11-11: DeepSeek wrote its prepare_order call as text and the customer read the markup. The call must run, and the markup must never be shown. */
class DsmlToolCallsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String LEAK = "<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"prepare_order\">\n"
            + "<｜DSML｜parameter name=\"area\" string=\"true\">الشروق</｜DSML｜parameter>\n"
            + "<｜DSML｜parameter name=\"customerName\" string=\"true\">محمود</｜DSML｜parameter>\n"
            + "<｜DSML｜parameter name=\"items\" string=\"false\">[{\"slug\":\"قميص\",\"quantity\":9,\"options\":{\"المقاس\":\"M\"}}]</｜DSML｜parameter>\n"
            + "<｜DSML｜parameter name=\"whatsappConsent\" string=\"false\">true</｜DSML｜parameter>\n"
            + "</｜DSML｜invoke>\n</｜DSML｜tool_calls>";

    Turn parse(String content) throws Exception {
        JsonNode root = JSON.readTree(JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))), "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1))));
        return new OpenAiProvider("deepseek", "DeepSeek", "k", "m", "low", "http://127.0.0.1:1/v1", 500, true, "").parse(root);
    }

    @Test
    void theLeakedCallIsReadAsACallAndTheTextIsClean() throws Exception {
        Turn t = parse(LEAK);
        assertThat(t.calls()).hasSize(1);
        ToolCall c = t.calls().get(0);
        assertThat(c.name()).isEqualTo("prepare_order");
        assertThat(c.args()).containsEntry("area", "الشروق").containsEntry("customerName", "محمود").containsEntry("whatsappConsent", true);
        assertThat(String.valueOf(c.args().get("items"))).contains("quantity=9").contains("M");
        assertThat(t.text()).doesNotContain("DSML").doesNotContain("｜");
        JsonNode sent = JSON.valueToTree(t.raw()).path("tool_calls");
        assertThat(sent.path(0).path("id").asText()).isNotBlank();
        assertThat(sent.path(0).path("function").path("name").asText()).isEqualTo("prepare_order");
    }

    @Test
    void spacesInsideTheMarkupAndTextBeforeItAreTolerated() throws Exception {
        Turn t = parse("تمام يا فندم\n< ｜ DSML ｜ invoke name=\"get_product\">< ｜ DSML ｜ parameter name=\"slug\" string=\"true\">قميص</ ｜ DSML ｜ parameter></ ｜ DSML ｜ invoke>");
        assertThat(t.calls()).extracting(ToolCall::name).containsExactly("get_product");
        assertThat(t.text()).isEqualTo("تمام يا فندم");
    }

    @Test
    void aCutOffCallNeverReachesTheCustomer() throws Exception {
        Turn t = parse("ثانية واحدة <｜DSML｜tool_calls><｜DSML｜invoke name=\"prepare_or");
        assertThat(t.text()).isEqualTo("ثانية واحدة");
        assertThat(DsmlToolCalls.strip("a <｜DSML｜invoke")).isEqualTo("a");
    }

    @Test
    void anOrdinaryReplyIsUntouched() throws Exception {
        Turn t = parse("القميص بـ ١٥٠ جنيه");
        assertThat(t.calls()).isEmpty();
        assertThat(t.text()).isEqualTo("القميص بـ ١٥٠ جنيه");
    }
}

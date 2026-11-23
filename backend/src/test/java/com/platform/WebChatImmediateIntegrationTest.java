package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The website chat has no waiting window by default (web-burst-ms = 0): the assistant starts the moment the customer sends, and the page learns of the answer from the history. */
@TestPropertySource(properties = { "app.chat.web-burst=true", "app.chat.web-burst-ms=0", "app.chat.burst-max-ms=4000" })
class WebChatImmediateIntegrationTest extends IntegrationTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void aSingleLineIsAnsweredAtOnceWithNoWaitingWindow() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "im" + System.nanoTime();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> Turn.model("أهلاً بيك يا فندم، تحت أمرك.")));
        long start = System.currentTimeMillis();
        JsonNode posted = JSON.readTree(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, tag + " السلام عليكم")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(posted.path("pending").asBoolean()).as("the line is saved and the answer comes through the history").isTrue();
        String answer = null;
        for (int i = 0; i < 60 && answer == null; i++) {
            JsonNode s = JSON.readTree(onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv + "&state=true", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for (JsonNode m : s.path("messages")) if ("assistant".equals(m.path("role").asText())) answer = m.path("content").asText();
            if (answer == null) Thread.sleep(50);
        }
        long took = System.currentTimeMillis() - start;
        assertThat(answer).as("answered").contains("تحت أمرك");
        assertThat(took).as("no 5 s (or any) quiet window before the assistant starts: %d ms", took).isLessThan(2500);
    }
}

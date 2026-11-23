package com.platform;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** CHAT_WEB_BURST=false: the website chat keeps its old behaviour, one request, one reply in the response. */
@TestPropertySource(properties = "app.chat.web-burst=false")
class WebChatSyncIntegrationTest extends IntegrationTestBase {
    @Test
    void withTheSwitchOffThePostAnswersInTheResponse() throws Exception {
        FakeAiProvider.reset();
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "zu" + System.nanoTime();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> Turn.model("اهلا بيك، الرد في نفس الطلب")));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s عايز فستان\"}".formatted(conv, tag)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("اهلا بيك، الرد في نفس الطلب")).andExpect(jsonPath("$.pending").doesNotExist());
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv + "&state=true", null).andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false)).andExpect(jsonPath("$.messages.length()").value(2));
    }
}

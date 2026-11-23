package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Live incident 2026-11-22: the model's own first answer to «عايز حاجة حلوة» (exact text) reached the customer with «اتحرشت باسم حضرتك؟». The last gate puts the sentence right. */
class GarbledNameAskIntegrationTest extends IntegrationTestBase {
    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    static final String DRAFT = "أهلاً بيك في نايل ستايل 🌟 اتحرشت باسم حضرتك؟\n\nوعشان أرشحلك حاجة تناسبك، قولي بتدور على إيه: لبس، حذاء، ولا شنطة مثلاً؟ ولبس الرجالي ولا الحريمي ولا للأطفال؟";

    @Test
    void theCustomerNeverReceivesTheGarbledVerb() throws Exception {
        Tenant t = onboard("STORE");
        for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model(DRAFT));   // the draft, and any rewrite the checks ask for, come back with the same garble
        String body = onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز حاجة حلوة\"}".formatted(UUID.randomUUID())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reply = JsonPath.read(body, "$.reply");
        assertThat(reply).as(reply).doesNotContain("اتحرشت").contains("أتشرف باسم حضرتك؟");
    }
}

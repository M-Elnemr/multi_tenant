package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Hand-over to the shop: an upset customer marks the chat for the owner, who replies from the inbox; the assistant stays quiet in that chat until it is handed back. */
class ChatHandoverIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String send(Tenant t, String conv, String text) throws Exception {
        return JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk())), "$.reply");
    }

    @Test
    void anUpsetCustomerIsHandedToTheOwnerWhoRepliesAndTheAssistantWaits() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("حقك عليا يا فندم 🙏 قولي حصل ايه وأنا معاك"));
        send(t, conv, "انتو نصابين الطلب اتأخر يوم كامل");

        // the chat waits for the team, and the owner was told
        String list = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations?needsYou=true", null).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(list, "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(list, "$[0].needsHumanReason")).contains("زعلان");
        String id = JsonPath.read(list, "$[0].id");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s) AND notification_type = 'CHAT_NEEDS_HUMAN'")
                .param("s", t.slug()).query(Long.class).single()).isPositive();

        // the owner answers in their own words: saved in the chat, the chat leaves the list, and the assistant pauses there
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + id + "/reply", "{\"message\":\"أنا صاحب المحل، هكلمك حالاً ونحل الموضوع\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.aiPaused").value(true)).andExpect(jsonPath("$.needsHuman").value(false)).andExpect(jsonPath("$.delivery").value("SAVED"));
        assertThat(body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv, null))).contains("أنا صاحب المحل");

        // while paused, the customer is told once that a person answers here, then nothing automatic goes out, and the model is never asked
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("must not be used"));
        assertThat(send(t, conv, "طيب مستني")).contains("فريق المحل");
        assertThat(send(t, conv, "؟؟")).isEmpty();
        assertThat(FakeAiProvider.SCRIPT).hasSize(1);

        // handed back: the assistant answers again
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + id + "/resume", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.aiPaused").value(false));
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بيك تاني يا فندم"));
        assertThat(send(t, conv, "تمام شكرا")).isNotBlank();
    }

    @Test
    void askingForAPersonIsHandedOverTooAndAnOrdinaryChatIsNot() throws Exception {
        Tenant t = onboard("STORE");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أكيد، هبلغ فريق المحل"));
        send(t, UUID.randomUUID().toString(), "عايز اكلم حد من المحل لو سمحت");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بيك"));
        send(t, UUID.randomUUID().toString(), "السلام عليكم عندكم شنط؟");
        assertThat(JsonPath.<Integer>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/needs-you-count", null)), "$.count")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations", null)), "$.length()")).isEqualTo(2);
    }

    @org.springframework.beans.factory.annotation.Autowired com.platform.chat.ChatController chatController;

    /** The owner turned the chat message limits off (2026-11-18): a chat is never refused for sending many messages; with app.chat.rate-limit.enabled the old limits come back. */
    @Test
    void theChatMessageLimitsAreOffByDefaultAndComeBackWhenEnabled() throws Exception {
        Tenant t = onboard("STORE");
        for (int i = 0; i < 45; i++) {   // many customers behind one address
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
            onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عندكم شنط؟\"}".formatted(UUID.randomUUID())).andExpect(status().isOk());
        }
        String conv = UUID.randomUUID().toString();
        int refused = 0;
        for (int i = 0; i < 40; i++) {   // one chat, more than the old 30 per 10 minutes
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
            if (onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"رسالة %d\"}".formatted(conv, i)).andReturn().getResponse().getStatus() == 429) refused++;
        }
        assertThat(refused).as("no limit on a chat").isZero();
        org.springframework.test.util.ReflectionTestUtils.setField(chatController, "rateLimitEnabled", true);
        try {
            String conv2 = UUID.randomUUID().toString();
            int refused2 = 0;
            for (int i = 0; i < 32; i++) {
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
                if (onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"رسالة %d\"}".formatted(conv2, i)).andReturn().getResponse().getStatus() == 429) refused2++;
            }
            assertThat(refused2).as("the old limit when switched on").isEqualTo(2);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(chatController, "rateLimitEnabled", false);
        }
    }

    /** The recommended production mode: real conversations (long, repeated «تمام», many customers on one address) pass; a script repeating one text or running one chat away is refused with a 429 before any model call. */
    @Test
    void abuseModeLetsRealCustomersThroughAndStopsScriptsBeforeAnyModelCall() throws Exception {
        Tenant t = onboard("STORE");
        org.springframework.test.util.ReflectionTestUtils.setField(chatController, "rateLimitMode", "abuse");
        try {
            String conv = UUID.randomUUID().toString();
            for (int i = 0; i < 40; i++) {   // a long, real chat with distinct messages: nothing refused
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
                onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"رسالة %d\"}".formatted(conv, i)).andExpect(status().isOk());
            }
            for (int i = 0; i < 3; i++) {   // «تمام» a few times is normal
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
                onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"تمام\"}".formatted(conv)).andExpect(status().isOk());
            }
            int refused = 0, calls = FakeAiProvider.SCRIPT.size();
            String flood = UUID.randomUUID().toString();
            for (int i = 0; i < 12; i++) {   // the same text again and again
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً"));
                if (onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"اشتري الان\"}".formatted(flood)).andReturn().getResponse().getStatus() == 429) refused++;
            }
            assertThat(refused).as("the same text beyond 6 in two minutes").isEqualTo(6);
            assertThat(FakeAiProvider.SCRIPT.size()).as("a refused message never reaches the model").isGreaterThanOrEqualTo(calls + 6);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(chatController, "rateLimitMode", "off");
        }
    }
}

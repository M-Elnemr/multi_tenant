package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** The website chat answers a burst of lines once: lines are saved on arrival, the assistant waits for the quiet window, reads them together, replies once; the history shows pending, then the reply. */
@TestPropertySource(properties = { "app.chat.web-burst=true", "app.chat.burst-ms=700", "app.chat.burst-max-ms=4000" })
class WebChatBurstIntegrationTest extends IntegrationTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    JsonNode post(Tenant t, String conv, String text) throws Exception {
        ResultActions r = onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk());
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    JsonNode state(Tenant t, String conv) throws Exception {
        return JSON.readTree(onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv + "&state=true", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void waitIdle(Tenant t, String conv) throws Exception {
        for (int i = 0; i < 400; i++) { if (!state(t, conv).path("pending").asBoolean()) return; Thread.sleep(50); }
        throw new AssertionError("the reply never finished");
    }

    List<String> roles(JsonNode s, String role) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : s.path("messages")) if (role.equals(m.path("role").asText())) out.add(m.path("content").asText());
        return out;
    }

    @Test
    void threeRapidLinesGetOneReplyThatCoversAllOfThem() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "zq" + System.nanoTime();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> {
            calls.incrementAndGet();
            seen.add(turns.get(turns.size() - 1).text());
            return Turn.model("تمام يا فندم، رديت على الكل مرة واحدة");
        }));
        JsonNode a = post(t, conv, tag + " عايز فستان");
        assertThat(a.path("pending").asBoolean()).isTrue();
        assertThat(a.path("messageId").asLong()).isPositive();
        Thread.sleep(150);
        post(t, conv, "لونه اسود");
        Thread.sleep(150);
        post(t, conv, "ومقاس ميديم");
        JsonNode during = state(t, conv);
        assertThat(during.path("pending").asBoolean()).as("the typing dots show while the assistant waits for the end of the burst").isTrue();
        assertThat(roles(during, "assistant")).as("nothing is answered line by line").isEmpty();
        assertThat(roles(during, "user")).hasSize(3);   // each line is saved at once, as its own bubble
        waitIdle(t, conv);
        JsonNode done = state(t, conv);
        assertThat(roles(done, "assistant")).as("ONE reply").hasSize(1).first().asString().contains("رديت على الكل");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get(0)).as("the model read all three lines together, as one turn").contains("عايز فستان").contains("لونه اسود").contains("ومقاس ميديم");
        assertThat(roles(done, "user")).hasSize(3);
        // the plain list (older callers) still works and carries the header
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv, null).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(4));
    }

    @Test
    void aLineWhileTheReplyIsBeingWrittenReplacesItWithOneAnswerOverEveryLine() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "zr" + System.nanoTime();
        AtomicInteger first = new AtomicInteger();
        List<String> second = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 6; i++) FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> {   // decided by what the model is shown, so any extra call of the pipeline gets the same answer
            String last = turns.get(turns.size() - 1).text();
            if (last != null && last.contains("وهتوصلي بكره")) { second.add(last); return Turn.model("الرد النهائي على الاتنين"); }
            first.incrementAndGet();   // the first reply takes a while
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            return Turn.model("الرد القديم");
        }));
        post(t, conv, tag + " عايز جاكيت");
        for (int i = 0; i < 100 && first.get() == 0; i++) Thread.sleep(50);   // the quiet window passed, the model is thinking
        assertThat(first.get()).isEqualTo(1);
        post(t, conv, "وهتوصلي بكره");   // arrives while the first reply is being written
        waitIdle(t, conv);
        JsonNode done = state(t, conv);
        assertThat(roles(done, "assistant")).as("the old reply was dropped, only the final one is saved").containsExactly("الرد النهائي على الاتنين");
        assertThat(second).hasSize(1);
        assertThat(second.get(0)).contains("عايز جاكيت").contains("وهتوصلي بكره");
        assertThat(roles(done, "user")).as("no customer line lost or saved twice").hasSize(2);
    }

    @Test
    void aLineAfterTheReplyWasSavedIsAnsweredByASecondReply() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "zs" + System.nanoTime();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> Turn.model("رد اول")));
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> Turn.model("رد تاني")));
        post(t, conv, tag + " عايز حذاء");
        waitIdle(t, conv);
        post(t, conv, "ومقاس 42 متاح؟");
        waitIdle(t, conv);
        assertThat(roles(state(t, conv), "assistant")).containsExactly("رد اول", "رد تاني");
    }

    @Test
    void typingHeartbeatIsAcceptedAndExtendsTheWait() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString(), tag = "zt" + System.nanoTime();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only(tag, turns -> Turn.model("رد واحد")));
        post(t, conv, tag + " عايز شنطة");
        long end = System.currentTimeMillis() + 1800;   // longer than the 700 ms window: only the heartbeat keeps the burst open
        while (System.currentTimeMillis() < end) {
            onHost(t.host(), null, "POST", "/api/v1/shop/chat/typing", "{\"conversationId\":\"%s\"}".formatted(conv)).andExpect(status().isOk());
            Thread.sleep(300);
        }
        assertThat(roles(state(t, conv), "assistant")).as("still waiting for the customer to finish").isEmpty();
        waitIdle(t, conv);
        assertThat(roles(state(t, conv), "assistant")).containsExactly("رد واحد");
    }
}

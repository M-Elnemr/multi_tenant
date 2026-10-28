package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** A question the assistant cannot answer reaches the owner, who answers on one screen; the assistant then carries the answer to the customer in the same chat. */
class OwnerQuestionsIntegrationTest extends IntegrationTestBase {
    static final String SECRET = "evolution-test-secret-0123456789";
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeEvolutionApi.reset(); FakeMetaGraph.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    ResultActions webhook(String raw) throws Exception {
        return mvc.perform(post("/api/v1/chat/evolution/webhook/" + SECRET).header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw));
    }

    static String message(String instance, String from, String id, String text) {
        return "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"%s\"},\"message\":{\"conversation\":\"%s\"}}}".formatted(instance, from, id, text);
    }

    String linkedInstance(Tenant t) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        return jdbc.sql("SELECT instance FROM chat.evolution_settings es JOIN core.tenants c ON c.id = es.tenant_id WHERE c.slug = :s").param("s", t.slug()).query(String.class).single();
    }

    List<FakeEvolutionApi.Call> texts() { return FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).toList(); }

    @Test
    void theOwnerIsToldAnswersOnOneScreenAndTheAssistantTellsTheCustomerOnWhatsApp() throws Exception {
        Tenant t = onboard("STORE");
        String inst = linkedInstance(t);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "Does the kids t-shirt come with a one year warranty?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("I asked the team, I will message you the answer here"));
        webhook(message(inst, "201055550000", "Q" + uniq(), "is the t-shirt cotton?")).andExpect(status().isOk());

        // the question is waiting for the owner, next to its customer and chat
        String list = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions", null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(list, "$[0].status")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(list, "$[0].customerPhone")).isEqualTo("+201055550000");
        assertThat((String) JsonPath.read(list, "$[0].channel")).isEqualTo("WHATSAPP");
        long id = ((Number) JsonPath.read(list, "$[0].id")).longValue();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions/open-count", null).andExpect(jsonPath("$.open").value(1));
        String detail = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions/" + id, null).andExpect(status().isOk()));
        assertThat(detail).contains("is the t-shirt cotton?").contains("I asked the team");

        // the owner was told: in the app and on the shop's own WhatsApp number, with the link to the screen
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'AI_QUESTION' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", t.slug()).query(Long.class).single()).isEqualTo(1);
        var toOwner = texts().stream().filter(c -> "201012345678".equals(c.body().get("number"))).findFirst().orElseThrow();
        assertThat((String) toOwner.body().get("text")).contains("Does the kids t-shirt come with a one year warranty?").contains("/dashboard/questions");

        // the owner answers: the assistant is handed the answer and words it for the customer, who gets it on WhatsApp
        AtomicReference<String> note = new AtomicReference<>();
        FakeAiProvider.SCRIPT.add(turns -> { note.set(turns.get(turns.size() - 1).text()); return Turn.model("Good news! It is 100% cotton. Shall I book size 6?"); });
        int before = texts().size();
        String answered = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/answer", "{\"answer\":\"Yes, 100% cotton\"}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(answered, "$.status")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(answered, "$.delivery")).isEqualTo("SENT");
        assertThat(note.get()).contains("Yes, 100% cotton").contains("Does the kids t-shirt come with a one year warranty?").contains("NOTE FROM THE SHOP");
        var toCustomer = texts().get(before);
        assertThat(toCustomer.body().get("number")).isEqualTo("201055550000");
        assertThat((String) toCustomer.body().get("text")).isEqualTo("Good news! It is 100% cotton. Shall I book size 6?");
        // the message is part of the chat, so the conversation simply continues
        assertThat(jdbc.sql("SELECT content FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.external_id = '201055550000' AND m.role = 'assistant' ORDER BY m.id DESC LIMIT 1").query(String.class).single()).startsWith("Good news!");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions/open-count", null).andExpect(jsonPath("$.open").value(0));

        // a question is answered once
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/answer", "{\"answer\":\"again\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ANSWERED"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/99999999/answer", "{\"answer\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void ifTheModelCannotWordItTheOwnersAnswerGoesAsItIsAndAFailedSendCanBeRetried() throws Exception {
        Tenant t = onboard("STORE");
        String inst = linkedInstance(t);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "هل في ضمان على التيشيرت ده؟")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("هسأل الفريق وارجعلك"));
        webhook(message(inst, "201066660000", "Q" + uniq(), "القماش قطن؟")).andExpect(status().isOk());
        long id = ((Number) JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions?status=OPEN", null)), "$[0].id")).longValue();

        // the model answers nothing: the owner's own words reach the customer, and a failed send is reported with a note
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("")); FakeAiProvider.SCRIPT.add(turns -> Turn.model(""));   // empty twice: the retry also gets nothing
        FakeEvolutionApi.FAIL_SEND = true;
        String failed = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/answer", "{\"answer\":\"قطن ١٠٠٪\"}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(failed, "$.delivery")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.deliveryNote")).contains("Copy the message");
        assertThat((String) JsonPath.read(failed, "$.replyText")).contains("قطن ١٠٠٪").contains("بخصوص سؤالك");

        // the phone is back: the same reply is sent again, nothing is generated a second time
        FakeEvolutionApi.FAIL_SEND = false;
        int before = texts().size();
        String retried = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/retry", "{}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(retried, "$.delivery")).isEqualTo("SENT");
        assertThat(texts().get(before).body().get("number")).isEqualTo("201066660000");
        assertThat((String) texts().get(before).body().get("text")).contains("قطن ١٠٠٪");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/retry", "{}").andExpect(status().isConflict());
    }

    @Test
    void onTheWebsiteTheAnswerWaitsInTheChatAndTheOwnerCanDismissAQuestion() throws Exception {
        Tenant t = onboard("STORE");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "Do you ship to Aswan?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("I asked the team; the answer will appear in this chat"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"ship to Aswan?\"}".formatted(conv)).andExpect(status().isOk());
        String list = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions", null));
        assertThat((String) JsonPath.read(list, "$[0].channel")).isEqualTo("WEB");
        long id = ((Number) JsonPath.read(list, "$[0].id")).longValue();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Yes, we ship to Aswan in 3 days!"));
        String answered = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + id + "/answer", "{\"answer\":\"Yes, 3 days\"}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(answered, "$.delivery")).isEqualTo("SAVED");
        // the customer sees it when they open the chat again
        String history = body(onHost(t.host(), null, "GET", "/api/v1/shop/chat/history?conversationId=" + conv, null).andExpect(status().isOk()));
        assertThat(history).contains("Yes, we ship to Aswan in 3 days!");
        assertThat(texts()).isEmpty();   // nothing is pushed on a channel that cannot receive it

        // a question the owner has nothing to say about is dismissed
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "What is the meaning of life?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"meaning of life?\"}".formatted(conv)).andExpect(status().isOk());
        long other = ((Number) JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions?status=OPEN", null)), "$[0].id")).longValue();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/questions/" + other + "/dismiss", "{}").andExpect(status().isNoContent());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions/open-count", null).andExpect(jsonPath("$.open").value(0));
        // asking the same open question twice is one question for the owner
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "Do you ship to Luxor?")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "do you ship to luxor?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("asked"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"luxor?\"}".formatted(conv)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/questions/open-count", null).andExpect(jsonPath("$.open").value(1));
    }
}

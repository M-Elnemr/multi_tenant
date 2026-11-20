package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.FrustrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** What happens when a customer is genuinely upset, in each of the shop's three modes: the assistant calms them, one fixed message and a hand-over, or silence and a hand-over. */
class FrustrationHandoverIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired FrustrationService frustration;
    @Autowired com.platform.billing.WalletService wallet;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tid(Tenant t) { return jdbc.sql("SELECT tenant_id FROM core.tenant_domains WHERE host = :h").param("h", t.host()).query(UUID.class).single(); }

    String say(Tenant t, String conv, String text) throws Exception {
        return JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk())), "$.reply");
    }

    void mode(Tenant t, String mode, String message) throws Exception {
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/frustration", "{\"mode\":\"%s\",\"message\":%s}".formatted(mode, message == null ? "null" : "\"" + message + "\"")).andExpect(status().isOk());
    }

    /** One scripted model turn for the chat whose messages hold {@code marker}: a background job of the app that reaches the queue first does not take it. */
    void script(String marker, String text) { FakeAiProvider.SCRIPT.add(FakeAiProvider.only(marker, turns -> Turn.model(text))); }

    long notices(Tenant t) { return jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type = 'CHAT_NEEDS_HUMAN'").param("t", tid(t)).query(Long.class).single(); }

    long assistantMessages(String conv) { return jdbc.sql("SELECT count(*) FROM chat.messages WHERE role = 'assistant' AND conversation_id = (SELECT id FROM chat.conversations WHERE external_id = :e)").param("e", conv).query(Long.class).single(); }

    java.util.Map<String, Object> chat(String conv) { return jdbc.sql("SELECT frustration_paused, unhappy, needs_human, takeover FROM chat.conversations WHERE external_id = :e").param("e", conv).query().singleRow(); }

    @Test
    void theDefaultModeKeepsTheAssistantAnsweringButCalmsTheCustomerFirst() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/frustration", null).andExpect(jsonPath("$.mode").value("AI")).andExpect(jsonPath("$.defaultMessage").exists());
        String conv = UUID.randomUUID().toString();
        script("mark" + conv, "حق حضرتك علينا، وأنا متفهم إن التأخير ده بيضايقك. خليني أراجع معاك حالة الطلب.");
        String reply = say(t, conv, "انتوا نصابين الطلب اتأخر ومحدش بيرد mark" + conv);
        assertThat(reply).contains("حق حضرتك");
        assertThat(FakeAiProvider.lastSystem).contains("THE CUSTOMER IS UPSET").contains("NEVER say you contacted the shop");
        assertThat(chat(conv).get("frustration_paused")).isEqualTo(false);   // the assistant stays in the chat
        // an ordinary complaint does not change the assistant's instructions
        String calm = UUID.randomUUID().toString();
        script("mark" + calm, "ok");
        say(t, calm, "السعر غالي شوية mark" + calm);
        assertThat(FakeAiProvider.lastSystem).doesNotContain("THE CUSTOMER IS UPSET");
    }

    @Test
    void theFixedMessageIsSentOnceTheChatIsFlaggedTheShopToldAndTheAssistantStaysOut() throws Exception {
        Tenant t = onboard("STORE");
        mode(t, "MESSAGE", "نعتذر لحضرتك، فريقنا هيتواصل معاك دلوقتي.");
        long before = wallet.balance(tid(t));
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("the assistant must not answer this"));
        String first = say(t, conv, "انتوا نصابين مش هتعامل معاكم تاني");
        assertThat(first).isEqualTo("نعتذر لحضرتك، فريقنا هيتواصل معاك دلوقتي.");
        assertThat(FakeAiProvider.SCRIPT).hasSize(1);   // the model was never asked
        var st = chat(conv);
        assertThat(st.get("frustration_paused")).isEqualTo(true);
        assertThat(st.get("unhappy")).isEqualTo(true);
        assertThat(st.get("needs_human")).isEqualTo(true);
        assertThat(notices(t)).isEqualTo(1L);
        assertThat(wallet.balance(tid(t))).as("a hand-over is not an AI turn: nothing is charged").isEqualTo(before);
        // everything the customer writes next is quiet: no second message, no second notice, no AI, no charge, however it is worded
        for (String more : new String[]{"ايه ده مفيش حد بيرد", "انتوا نصابين تاني", "hello?", "السلام عليكم"}) assertThat(say(t, conv, more)).as(more).isEmpty();
        assertThat(assistantMessages(conv)).isEqualTo(1L);
        assertThat(notices(t)).isEqualTo(1L);
        assertThat(FakeAiProvider.SCRIPT).hasSize(1);
        assertThat(wallet.balance(tid(t))).isEqualTo(before);
        // the inbox says why the assistant is quiet, and the shop resumes it by hand
        String id = jdbc.sql("SELECT id::text FROM chat.conversations WHERE external_id = :e").param("e", conv).query(String.class).single();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + id, null).andExpect(jsonPath("$.frustrationPaused").value(true)).andExpect(jsonPath("$.aiPausedReason").value("FRUSTRATION")).andExpect(jsonPath("$.aiActive").value(false));
        // the shop can filter the handed-over chats and sees how many there are
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations?page=true&status=FRUSTRATED", null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].frustrationPaused").value(true));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/counts", null).andExpect(jsonPath("$.frustrated").value(1));
        FakeAiProvider.SCRIPT.clear();
        script("mark" + conv, "Welcome back, thank you for your patience.");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + id + "/resume", null).andExpect(status().isOk());
        assertThat(chat(conv).get("frustration_paused")).isEqualTo(false);
        assertThat(say(t, conv, "ايه الأسعار عندكم mark" + conv)).contains("Welcome back");   // the assistant answers again, from the customer's next message
    }

    @Test
    void silentModeSendsNothingButFlagsPausesAndTellsTheShop() throws Exception {
        Tenant t = onboard("STORE");
        mode(t, "SILENT", null);
        String conv = UUID.randomUUID().toString();
        assertThat(say(t, conv, "عايز اكلم حد من المحل")).isEmpty();
        assertThat(assistantMessages(conv)).isZero();
        assertThat(chat(conv).get("frustration_paused")).isEqualTo(true);
        assertThat(notices(t)).isEqualTo(1L);
        assertThat(say(t, conv, "فين الرد")).isEmpty();
        assertThat(notices(t)).isEqualTo(1L);
        assertThat(FakeAiProvider.SCRIPT).isEmpty();
    }

    @Test
    void anEnglishCustomerGetsTheEnglishDefaultAndAnOrdinaryComplaintIsNotHandedOver() throws Exception {
        Tenant t = onboard("STORE");
        mode(t, "MESSAGE", null);
        String ordinary = UUID.randomUUID().toString();
        script("mark" + ordinary, "Thank you, your message is noted.");
        assertThat(say(t, ordinary, "this is a bit expensive, do you have another size? mark" + ordinary)).contains("is noted");
        assertThat(chat(ordinary).get("frustration_paused")).isEqualTo(false);
        String conv = UUID.randomUUID().toString();
        String reply = say(t, conv, "this is unacceptable, I want to speak to a manager");
        assertThat(reply).contains("customer service team");
        String ar = UUID.randomUUID().toString();
        assertThat(say(t, ar, "انتوا نصابين")).contains("فريق خدمة العملاء");   // the Arabic default
    }

    @Test
    void simultaneousOrRepeatedDeliveriesHandOverOnceAndTheWalletDoesNotChangeTheDecision() throws Exception {
        Tenant t = onboard("STORE");
        mode(t, "MESSAGE", "رسالة التحويل");
        UUID tenant = tid(t);
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 0 WHERE tenant_id = :t").param("t", tenant).update();   // an empty wallet: the assistant could not answer anyway
        String conv = UUID.randomUUID().toString();
        say(t, conv, "ازيك");   // creates the chat (the empty wallet path: the shop is told once already, for the empty wallet)
        long told = notices(t);
        UUID id = jdbc.sql("SELECT id FROM chat.conversations WHERE external_id = :e").param("e", conv).query(UUID.class).single();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', 'انتوا نصابين')").param("c", id).update();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<FrustrationService.Handover>> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) all.add(pool.submit(() -> frustration.handle(tenant, id, "WEB", false)));
        List<String> texts = new ArrayList<>();
        for (var f : all) texts.add(f.get().text());
        pool.shutdown();
        assertThat(texts.stream().filter(x -> !x.isEmpty())).as("the fixed message goes out once").hasSize(1);
        assertThat(notices(t) - told).as("the shop is told once about the hand-over").isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND role = 'assistant' AND content = 'رسالة التحويل'").param("c", id).query(Long.class).single()).isEqualTo(1L);
        assertThat(wallet.balance(tenant)).isZero();
    }

    @Test
    void theSettingIsValidatedAndEachShopHasItsOwn() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        onHost(a.host(), a.access(), "PUT", "/api/v1/store/ai/frustration", "{\"mode\":\"NOPE\"}").andExpect(status().isBadRequest());
        onHost(a.host(), a.access(), "PUT", "/api/v1/store/ai/frustration", "{\"mode\":\"MESSAGE\",\"message\":\"%s\"}".formatted("x".repeat(601))).andExpect(status().isBadRequest());
        mode(a, "SILENT", null);
        onHost(b.host(), b.access(), "GET", "/api/v1/store/ai/frustration", null).andExpect(jsonPath("$.mode").value("AI"));
        // shop B's customer is not affected by shop A's mode
        String conv = UUID.randomUUID().toString();
        script("mark" + conv, "حق حضرتك علينا وأنا متفهم إن ده بيضايقك.");
        assertThat(say(b, conv, "انتوا نصابين mark" + conv)).contains("حق حضرتك");
    }
}

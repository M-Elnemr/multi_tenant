package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The unified inbox: website and WhatsApp chats in one list with their channel, statuses from real timestamps (needs reply, waiting 30 minutes, unhappy, takeover),
 * OR-within / AND-across filters, the customer link, per-chat takeover (and the race with an AI reply in flight), resume without sending, the channel window rules,
 * notes, saved replies, AI Assist that never sends, and one shop never seeing another's chats.
 */
class InboxIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    void connectWhatsApp(Tenant t) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted("7" + System.nanoTime())).andExpect(status().isCreated());
    }

    UUID conv(UUID tenant, String channel, String ext) {
        return jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = :c AND external_id = :e").param("t", tenant).param("c", channel).param("e", ext).query(UUID.class).single();
    }

    String list(Tenant t, String query) throws Exception { return body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations?page=true" + query, null).andExpect(status().isOk())); }

    @Test
    void websiteAndWhatsAppChatsShareOneInboxWithStatusesFiltersAndTheCustomerLink() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        connectWhatsApp(t);
        String web = UUID.randomUUID().toString(), wa = "2010" + (10_000_000 + System.nanoTime() % 80_000_000);
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"do you have cotton shirts\"}".formatted(web)).andExpect(status().isOk());
        chat.reply(tenant, "WHATSAPP", wa, null, "عندكم قمصان قطن؟", "Mona Ali");
        UUID webConv = conv(tenant, "WEB", web), waConv = conv(tenant, "WHATSAPP", wa);

        // both chats, each with its own channel; the AI answered, so neither needs a reply yet
        String all = list(t, "");
        assertThat((Integer) JsonPath.read(all, "$.total")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(all, "$.data[*].channel")).containsExactlyInAnyOrder("WEB", "WHATSAPP");
        assertThat(JsonPath.<List<Object>>read(all, "$.data[?(@.statuses contains 'NEEDS_REPLY')]")).isEmpty();

        // 31 minutes of silence after the AI's answer: WAITING (from the timestamps, no job)
        jdbc.sql("UPDATE chat.conversations SET last_reply_at = now() - interval '31 minutes', last_customer_at = now() - interval '32 minutes' WHERE id = :c").param("c", webConv).update();
        assertThat(JsonPath.<List<String>>read(list(t, "&status=WAITING"), "$.data[*].id")).containsExactly(webConv.toString());

        // the owner takes the WhatsApp chat over; the customer writes again; the AI stays quiet (no note either) -> NEEDS_REPLY + TAKEOVER, and only that chat is paused
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + waConv + "/takeover", null).andExpect(status().isOk()).andExpect(jsonPath("$.takeover").value(true)).andExpect(jsonPath("$.aiActive").value(false));
        long before = jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND role = 'assistant'").param("c", waConv).query(Long.class).single();
        var r = chat.reply(tenant, "WHATSAPP", wa, null, "طيب بكام؟", null);
        assertThat(r.text()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND role = 'assistant'").param("c", waConv).query(Long.class).single()).isEqualTo(before);
        String needs = list(t, "&status=NEEDS_REPLY");
        assertThat(JsonPath.<List<String>>read(needs, "$.data[*].id")).containsExactly(waConv.toString());
        assertThat(JsonPath.<List<String>>read(needs, "$.data[0].statuses")).contains("NEEDS_REPLY", "TAKEOVER", "OPEN");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + webConv, null).andExpect(jsonPath("$.aiActive").value(true));

        // OR inside a group, AND across groups
        assertThat((Integer) JsonPath.read(list(t, "&status=NEEDS_REPLY,WAITING"), "$.total")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(list(t, "&status=NEEDS_REPLY,WAITING&channel=WEB"), "$.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list(t, "&status=NEEDS_REPLY&channel=WEB"), "$.total")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(list(t, "&ai=PAUSED"), "$.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list(t, "&channel=WEB&channel=WHATSAPP&since=1h"), "$.total")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(list(t, "&q=cotton"), "$.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(list(t, "&q=" + wa.substring(4)), "$.total")).isEqualTo(1);
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations?page=true&status=NOPE", null).andExpect(status().isBadRequest());

        // the WhatsApp number belongs to a customer with an order history: the chat links to the profile
        UUID customer = jdbc.sql("INSERT INTO commerce.customers (tenant_id, customer_number, name, phone) VALUES (:t, 'C-' || :n, 'Mona Ali', :p) RETURNING id")
                .param("t", tenant).param("n", uniq()).param("p", "+" + wa).query(UUID.class).single();
        String detail = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + waConv, null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(detail, "$.customerId")).isEqualTo(customer.toString());
        assertThat((String) JsonPath.read(detail, "$.customer.name")).isEqualTo("Mona Ali");
        assertThat((String) JsonPath.read(detail, "$.events[0].kind")).isEqualTo("TAKEOVER");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/customers/" + customer, null).andExpect(status().isOk()).andExpect(jsonPath("$.conversations[0].id").value(waConv.toString()));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/customers/" + customer + "/tags", "{\"tag\":\"VIP\"}").andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("VIP"));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/customers/" + customer + "/notes", "{\"notes\":\"prefers evening delivery\"}").andExpect(status().isOk());
        assertThat((Integer) JsonPath.read(list(t, "&customer=NO_ORDERS"), "$.total")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(list(t, "&customer=WITH_ORDERS"), "$.total")).isEqualTo(0);

        // resume: the AI is back for the next message, and nothing is sent now
        long msgs = jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", waConv).query(Long.class).single();
        int sent = FakeMetaGraph.SENT.size();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + waConv + "/resume", null).andExpect(status().isOk()).andExpect(jsonPath("$.takeover").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", waConv).query(Long.class).single()).isEqualTo(msgs);
        assertThat(FakeMetaGraph.SENT).hasSize(sent);

        // another shop cannot read or act on these chats
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "GET", "/api/v1/store/chat/conversations/" + waConv, null).andExpect(status().isNotFound());
        onHost(other.host(), other.access(), "POST", "/api/v1/store/chat/conversations/" + waConv + "/takeover", null).andExpect(status().isNotFound());
        assertThat((Integer) JsonPath.read(list(other, ""), "$.total")).isZero();
    }

    @Test
    void anAiAnswerStillThinkingWhenTheOwnerTakesOverIsDropped() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        String web = UUID.randomUUID().toString();
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"show me jackets\"}".formatted(web)).andExpect(status().isOk());
        UUID c = conv(tenant, "WEB", web);
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> {   // while the model is "thinking", the owner presses Take over
            jdbc.sql("UPDATE chat.conversations SET takeover = TRUE WHERE id = :c").param("c", c).update();
            return Turn.model("Sure! We have three jackets for you.");
        });
        String reply = JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"which colors do you have\"}".formatted(web)).andExpect(status().isOk())), "$.reply");
        assertThat(reply).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c AND content LIKE 'Sure! We have three jackets%'").param("c", c).query(Long.class).single()).isZero();
    }

    @Test
    void eachChannelHasItsOwnMessagingWindowAndOpenOnThePlatformLink() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        connectWhatsApp(t);
        String wa = "2011" + (10_000_000 + System.nanoTime() % 80_000_000), web = UUID.randomUUID().toString();
        chat.reply(tenant, "WHATSAPP", wa, null, "hello", "Karim");
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"hello\"}".formatted(web)).andExpect(status().isOk());
        UUID waConv = conv(tenant, "WHATSAPP", wa), webConv = conv(tenant, "WEB", web);
        jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now())").param("t", tenant).param("n", wa).update();   // what a real inbound WhatsApp message records

        // WhatsApp Business inside the window: can send, with a countdown and the wa.me link
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + waConv, null).andExpect(jsonPath("$.window.canSend").value(true)).andExpect(jsonPath("$.window.mode").value("CLOUD"))
                .andExpect(jsonPath("$.window.endsAt").isNotEmpty()).andExpect(jsonPath("$.window.openUrl").value("https://wa.me/" + wa));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + waConv + "/reply", "{\"message\":\"أهلاً {name} من {shop}\"}").andExpect(status().isOk()).andExpect(jsonPath("$.delivery").value("SENT"));
        assertThat(FakeMetaGraph.SENT.get(FakeMetaGraph.SENT.size() - 1).body().toString()).contains("أهلاً Karim من").doesNotContain("{name}");

        // 24 hours later the WhatsApp Business window is closed: the composer is closed and a send is refused; the website chat is not affected
        jdbc.sql("UPDATE chat.conversations SET last_customer_at = now() - interval '24 hours' WHERE tenant_id = :t").param("t", tenant).update();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + waConv, null).andExpect(jsonPath("$.window.canSend").value(false)).andExpect(jsonPath("$.window.reason").value("CLOSED"))
                .andExpect(jsonPath("$.window.openUrl").value("https://wa.me/" + wa));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + waConv + "/reply", "{\"message\":\"are you there?\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WINDOW_CLOSED"));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + webConv, null).andExpect(jsonPath("$.window.canSend").value(true)).andExpect(jsonPath("$.window.mode").value("WEB"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + webConv + "/reply", "{\"message\":\"we are here\"}").andExpect(status().isOk()).andExpect(jsonPath("$.delivery").value("SAVED"));
    }

    @Test
    void notesSavedRepliesUnhappyTagCloseAndAiAssistThatNeverSends() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        String web = UUID.randomUUID().toString();
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"الطلب اتأخر يومين ومحدش بيرد\"}".formatted(web)).andExpect(status().isOk());
        UUID c = conv(tenant, "WEB", web);
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"انتو نصابين وعايز فلوسي\"}".formatted(web)).andExpect(status().isOk());
        String d = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + c, null).andExpect(status().isOk()));
        assertThat((Boolean) JsonPath.read(d, "$.unhappy")).isTrue();
        assertThat((String) JsonPath.read(d, "$.unhappyReason")).contains("نصابين");
        assertThat((Integer) JsonPath.read(list(t, "&status=UNHAPPY"), "$.total")).isEqualTo(1);
        // the owner says it is fine: the tag goes and the same words do not bring it back
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + c + "/unhappy", "{\"on\":false}").andExpect(jsonPath("$.unhappy").value(false));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"تمام شكرا\"}".formatted(web)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + c, null).andExpect(jsonPath("$.unhappy").value(false));

        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + c + "/notes", "{\"body\":\"call her tomorrow\"}").andExpect(status().isOk()).andExpect(jsonPath("$[0].body").value("call her tomorrow"));
        String saved = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/saved-replies", "{\"title\":\"Hi\",\"body\":\"Welcome to {shop}\"}").andExpect(status().isCreated()));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/saved-replies", null).andExpect(jsonPath("$[0].id").value((String) JsonPath.read(saved, "$.id")));

        long msgs = jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", c).query(Long.class).single();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("آسفين جداً على التأخير يا فندم، هنتابع طلبك حالاً."));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + c + "/assist", "{\"action\":\"SUGGEST\"}").andExpect(status().isOk()).andExpect(jsonPath("$.text").value("آسفين جداً على التأخير يا فندم، هنتابع طلبك حالاً."));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + c + "/assist", "{\"action\":\"SHORTER\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages WHERE conversation_id = :c").param("c", c).query(Long.class).single()).isEqualTo(msgs);

        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/" + c + "/close", null).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat((Integer) JsonPath.read(list(t, "&status=CLOSED"), "$.total")).isEqualTo(1);
        // the customer writes again: the chat reopens by itself
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"any news?\"}".formatted(web)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/" + c, null).andExpect(jsonPath("$.status").value("OPEN"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/chat/conversations/bulk", "{\"ids\":[\"" + c + "\"],\"action\":\"close\"}").andExpect(jsonPath("$.changed").value(1));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/counts", null).andExpect(status().isOk()).andExpect(jsonPath("$.needsReply").isNumber());
    }
}

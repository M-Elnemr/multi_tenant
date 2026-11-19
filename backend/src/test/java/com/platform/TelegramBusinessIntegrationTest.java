package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.SocialBridge;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Telegram Business: link the owner's Telegram to the shop, the Business connection, customers answered as the owner, and the safeguards. Telegram and the model are faked. */
class TelegramBusinessIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired JdbcClient jdbc;
    @Autowired SocialBridge bridge;

    @BeforeEach
    void clean() {
        FakeAiProvider.reset();
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":4242}}");
        FakeHttpGateway.when("POST", "/sendPhoto", 200, "{\"ok\":true,\"result\":{\"message_id\":4243}}");
    }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    ResultActions webhook(String raw, String secret) throws Exception {
        var b = post("/api/v1/chat/telegram/webhook").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content(raw);
        if (secret != null) b.header("X-Telegram-Bot-Api-Secret-Token", secret);
        return mvc.perform(b);
    }

    void send(String raw) throws Exception { webhook(raw, "tgsecret").andExpect(status().isOk()); }

    String tid(Tenant t) { return jdbc.sql("SELECT tenant_id::text FROM core.tenant_domains WHERE host = :h").param("h", t.host()).query(String.class).single(); }

    static String num() { return String.valueOf(100000000L + (System.nanoTime() % 899999999L)); }

    String start(String owner, String code) { return "{\"update_id\":1,\"message\":{\"message_id\":1,\"from\":{\"id\":%s,\"first_name\":\"Owner\"},\"chat\":{\"id\":%s,\"type\":\"private\"},\"text\":\"/start %s\"}}".formatted(owner, owner, code); }

    String connection(String id, String owner, boolean enabled, boolean canReply) {
        return "{\"update_id\":2,\"business_connection\":{\"id\":\"%s\",\"user\":{\"id\":%s,\"first_name\":\"Owner\",\"username\":\"owner_shop\"},\"user_chat_id\":%s,\"date\":1,\"rights\":{\"can_reply\":%s},\"is_enabled\":%s}}".formatted(id, owner, owner, canReply, enabled);
    }

    String customer(String conn, String chat, String from, int id, String text) {
        return "{\"update_id\":3,\"business_message\":{\"message_id\":%d,\"business_connection_id\":\"%s\",\"from\":{\"id\":%s,\"first_name\":\"Sara\"},\"chat\":{\"id\":%s,\"type\":\"private\"},\"date\":1,\"text\":\"%s\"}}".formatted(id, conn, from, chat, text);
    }

    /** The shop's owner links Telegram and connects the bot; returns the Business connection id. */
    String connect(Tenant t, String owner) throws Exception {
        String url = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/telegram/link", null).andExpect(status().isOk())), "$.url");
        assertThat(url).startsWith("https://t.me/elmanassa_test_bot?start=");
        send(start(owner, url.substring(url.indexOf("start=") + 6)));
        String conn = "bc-" + uniq();
        send(connection(conn, owner, true, true));
        return conn;
    }

    List<JsonNode> sent(String method) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (var c : FakeHttpGateway.calls("POST", "/" + method)) out.add(JSON.readTree(c.body()));
        return out;
    }

    /** What went to customers (through the Business connection), not the bot's notes to the owner. */
    List<JsonNode> toCustomers() throws Exception { return sent("sendMessage").stream().filter((n) -> n.has("business_connection_id")).toList(); }

    @Test
    void onlyTelegramsOwnSecretIsAccepted() throws Exception {
        webhook("{\"update_id\":1}", null).andExpect(status().isForbidden());
        webhook("{\"update_id\":1}", "wrong").andExpect(status().isForbidden());
        webhook("{\"update_id\":1}", "tgsecret").andExpect(status().isOk());
    }

    @Test
    void theOwnerLinksTelegramConnectsTheBotAndCustomersAreAnsweredAsTheOwner() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/telegram", null).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.channel").doesNotExist());
        String owner = num();
        String conn = connect(t, owner);
        // the bot told the owner what to do next, with the shop's name
        assertThat(sent("sendMessage").stream().map(JsonNode::toString).toList()).anyMatch((m) -> m.contains("Linked") && m.contains("Chatbots"));
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/telegram", null).andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.channel.status").value("ACTIVE")).andExpect(jsonPath("$.channel.name").value("@owner_shop"));
        assertThat(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/channels", null))).contains("TELEGRAM").doesNotContain("token");

        // a customer writes to the owner's Telegram: the assistant answers through the Business connection, in that chat
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بحضرتك! تحب أساعدك في إيه؟"));
        String chat = num();
        FakeHttpGateway.CALLS.clear();
        send(customer(conn, chat, chat, 10, "مرحبا عايز اشتري"));
        JsonNode reply = sent("sendMessage").get(0);
        assertThat(reply.path("business_connection_id").asText()).isEqualTo(conn);
        assertThat(reply.path("chat_id").asLong()).isEqualTo(Long.parseLong(chat));
        assertThat(reply.path("text").asText()).contains("أهلاً");
        // the conversation is a Telegram one and shows in the inbox with the customer's name
        assertThat(jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = CAST(:t AS uuid) AND channel = 'TELEGRAM' AND external_id = :e").param("t", tid(t)).param("e", chat).query(Long.class).single()).isEqualTo(1L);

        // the same delivery again (Telegram resends) is answered once; the owner typing in their own chat is not a customer
        FakeHttpGateway.CALLS.clear();
        send(customer(conn, chat, chat, 10, "مرحبا عايز اشتري"));
        send(customer(conn, chat, owner, 11, "ثواني وأرجعلك"));
        assertThat(sent("sendMessage")).isEmpty();
    }

    @Test
    void theLinkIsOneTimeAndAConnectionOfAnUnlinkedPersonIsRefused() throws Exception {
        Tenant t = onboard("STORE");
        Tenant other = onboard("STORE");
        String url = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/telegram/link", null)), "$.url");
        String code = url.substring(url.indexOf("start=") + 6);
        String owner = num();
        send(start(owner, code));
        FakeHttpGateway.CALLS.clear();
        send(start(num(), code));   // someone else using the same code finds it spent
        assertThat(sent("sendMessage").stream().map(JsonNode::toString).toList()).anyMatch((m) -> m.contains("Open the link"));
        // a person who never linked cannot connect a Business account to any shop
        String stranger = num();
        FakeHttpGateway.CALLS.clear();
        send(connection("bc-" + uniq(), stranger, true, true));
        assertThat(jdbc.sql("SELECT count(*) FROM chat.channels WHERE platform = 'TELEGRAM' AND owner_ref = :o").param("o", stranger).query(Long.class).single()).isZero();
        // another shop sees nothing of this one's connection
        onHost(other.host(), other.access(), "GET", "/api/v1/store/ai/telegram", null).andExpect(jsonPath("$.linked").value(false));
    }

    @Test
    void aConnectionThatIsSwitchedOffOrCannotReplyAnswersNobody() throws Exception {
        Tenant t = onboard("STORE");
        String owner = num();
        String conn = connect(t, owner);
        send(connection(conn, owner, true, false));   // the owner took away the right to reply
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/telegram", null).andExpect(jsonPath("$.channel.status").value("PAUSED"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("should not be sent"));
        FakeHttpGateway.CALLS.clear();
        String chat = num();
        send(customer(conn, chat, chat, 20, "hello"));
        assertThat(toCustomers()).isEmpty();
        send(connection(conn, owner, true, true));   // allowed again: it answers
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Welcome! How can I help?"));
        FakeHttpGateway.CALLS.clear();
        send(customer(conn, chat, chat, 21, "hello again"));
        assertThat(toCustomers()).hasSize(1);
        send(connection(conn, owner, false, true));   // the bot was removed from the Business account
        FakeHttpGateway.CALLS.clear();
        send(customer(conn, chat, chat, 22, "anyone there"));
        assertThat(toCustomers()).isEmpty();
    }

    @Test
    void messagesTheShopSendsItselfGoOutOnlyInsideTheChatWindow() throws Exception {
        Tenant t = onboard("STORE");
        String owner = num();
        String conn = connect(t, owner);
        String chat = num();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello!"));
        send(customer(conn, chat, chat, 30, "hi"));
        FakeHttpGateway.CALLS.clear();
        assertThat(bridge.deliver(java.util.UUID.fromString(tid(t)), "TELEGRAM", chat, "Your order shipped", null, null)).isTrue();
        assertThat(sent("sendMessage").get(0).path("text").asText()).isEqualTo("Your order shipped");
        // a picture goes with its text as the caption
        FakeHttpGateway.CALLS.clear();
        assertThat(bridge.deliver(java.util.UUID.fromString(tid(t)), "TELEGRAM", chat, "New arrival", "https://img.test/a.jpg", null)).isTrue();
        assertThat(sent("sendPhoto").get(0).path("photo").asText()).isEqualTo("https://img.test/a.jpg");
        // the customer wrote more than a day ago: Telegram would refuse, so nothing is sent
        jdbc.sql("UPDATE chat.messages SET created_at = now() - interval '2 days' WHERE conversation_id IN (SELECT id FROM chat.conversations WHERE tenant_id = CAST(:t AS uuid) AND channel = 'TELEGRAM')").param("t", tid(t)).update();
        FakeHttpGateway.CALLS.clear();
        assertThat(bridge.deliver(java.util.UUID.fromString(tid(t)), "TELEGRAM", chat, "Late news", null, null)).isFalse();
        assertThat(sent("sendMessage")).isEmpty();
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import com.platform.chat.CommentService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Comments on the shop's posts (stored with their platform, auto-replied with the owner's stamp or the AI, a private message to the commenter, the owner's own reply),
 * and the AI seller's identity: it introduces itself by the name the shop gave it and asks for the customer's name (and, on the website, the phone number).
 */
class CommentsAndIdentityIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired CommentService comments;
    @Autowired ChatService chat;
    @Autowired WalletService wallet;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    @Test
    void commentsAreCollectedAnsweredWithTheStampAndFollowedByAnAiPrivateMessage() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        String page = "9" + (100_000_000 + System.nanoTime() % 800_000_000);
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"PAGE-OF-%s-token-0123456789\"}".formatted(page, page)).andExpect(status().isCreated());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/comments/settings", "{\"publicReply\":\"STAMP\",\"stampText\":\"تم الرد عليك في الخاص ❤️\",\"dmEnabled\":true,\"dmOnlyQuestions\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicReply").value("STAMP"));
        long credit = wallet.balance(tenant);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً يا منى 🌸 شفت تعليقك على الفستان، متاح بمقاسات S لـ XL. تحبي أي مقاس؟"));

        String c1 = "post_1_" + uniq();
        comments.onChange("FACEBOOK", page, json.readTree("""
                {"field":"feed","value":{"item":"comment","verb":"add","comment_id":"%s","post_id":"post_1","from":{"id":"5551","name":"Mona Ali"},"message":"بكام الفستان ده؟"}}
                """.formatted(c1)));
        // the page's own comment and a duplicate delivery are ignored
        comments.onChange("FACEBOOK", page, json.readTree("{\"field\":\"feed\",\"value\":{\"item\":\"comment\",\"verb\":\"add\",\"comment_id\":\"x_%s\",\"post_id\":\"post_1\",\"from\":{\"id\":\"%s\"},\"message\":\"thanks\"}}".formatted(uniq(), page)));
        comments.onChange("FACEBOOK", page, json.readTree("{\"field\":\"feed\",\"value\":{\"item\":\"comment\",\"verb\":\"add\",\"comment_id\":\"%s\",\"post_id\":\"post_1\",\"from\":{\"id\":\"5551\",\"name\":\"Mona Ali\"},\"message\":\"بكام الفستان ده؟\"}}".formatted(c1)));

        String list = onHost(t.host(), t.access(), "GET", "/api/v1/store/comments", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(list, "$.total")).isEqualTo(1);
        assertThat((String) JsonPath.read(list, "$.data[0].platform")).isEqualTo("FACEBOOK");
        assertThat((Boolean) JsonPath.read(list, "$.data[0].question")).isTrue();
        assertThat((String) JsonPath.read(list, "$.data[0].repliedBy")).isEqualTo("STAMP");
        assertThat((String) JsonPath.read(list, "$.data[0].dmStatus")).isEqualTo("SENT");
        assertThat(FakeMetaGraph.SENT.stream().anyMatch(x -> x.path().equals(c1 + "/comments") && x.body().get("message").equals("تم الرد عليك في الخاص ❤️"))).isTrue();
        assertThat(FakeMetaGraph.SENT.stream().anyMatch(x -> x.path().equals("me/messages") && x.body().toString().contains("comment_id=" + c1))).isTrue();
        assertThat(wallet.balance(tenant)).isEqualTo(credit - 10);   // the stamp is free, the AI private message costs 0.1 EGP
        // the private message is in the commenter's chat (kind COMMENT_DM), and today's counts and cost are in the settings
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND c.channel = 'FACEBOOK' AND c.external_id = '5551' AND m.kind = 'COMMENT_DM'").param("t", tenant).query(Long.class).single()).isEqualTo(1L);
        onHost(t.host(), t.access(), "GET", "/api/v1/store/comments/settings", null).andExpect(jsonPath("$.todayReplies").value(0)).andExpect(jsonPath("$.todayDms").value(1))
                .andExpect(jsonPath("$.todayCostMinor").value(10)).andExpect(jsonPath("$.messagePriceMinor").value(10));

        // a plain comment (no question) gets the stamp but no private message (only questions)
        String c2 = "post_1_" + uniq();
        comments.onChange("FACEBOOK", page, json.readTree("{\"field\":\"feed\",\"value\":{\"item\":\"comment\",\"verb\":\"add\",\"comment_id\":\"%s\",\"post_id\":\"post_1\",\"from\":{\"id\":\"5552\",\"name\":\"Hany\"},\"message\":\"جميل جدا\"}}".formatted(c2)));
        String id2 = jdbc.sql("SELECT id::text FROM chat.social_comments WHERE comment_id = :c").param("c", c2).query(String.class).single();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/comments?status=REPLIED", null).andExpect(jsonPath("$.total").value(2));
        assertThat(jdbc.sql("SELECT dm_status FROM chat.social_comments WHERE comment_id = :c").param("c", c2).query(String.class).single()).isEqualTo("NONE");
        // the owner replies himself and can send the private message too; a second one is refused
        onHost(t.host(), t.access(), "POST", "/api/v1/store/comments/" + id2 + "/reply", "{\"text\":\"شكراً يا هاني ❤️\"}").andExpect(status().isOk()).andExpect(jsonPath("$.repliedBy").value("OWNER"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/comments/" + id2 + "/dm", "{\"text\":\"أهلاً يا هاني، تحب نبعتلك الكتالوج؟\"}").andExpect(status().isOk()).andExpect(jsonPath("$.dmStatus").value("SENT"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/comments/" + id2 + "/dm", "{\"text\":\"again\"}").andExpect(status().isConflict());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/chat/conversations/counts", null).andExpect(jsonPath("$.unrepliedComments").value(0));
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "POST", "/api/v1/store/comments/" + id2 + "/reply", "{\"text\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void anAiPublicReplyCostsPointOneEgpAndWithoutCreditNothingIsSent() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        String page = "9" + (100_000_000 + System.nanoTime() % 800_000_000);
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"PAGE-OF-%s-token-0123456789\"}".formatted(page, page)).andExpect(status().isCreated());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/comments/settings", "{\"publicReply\":\"AI\",\"dmEnabled\":false}").andExpect(status().isOk());
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 15 WHERE tenant_id = :t").param("t", tenant).update();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح بمقاسات S لـ XL 🌸"));
        String c1 = "post_2_" + uniq();
        comments.onChange("FACEBOOK", page, json.readTree("{\"field\":\"feed\",\"value\":{\"item\":\"comment\",\"verb\":\"add\",\"comment_id\":\"%s\",\"post_id\":\"post_2\",\"from\":{\"id\":\"6661\",\"name\":\"Mona\"},\"message\":\"متاح مقاس M؟\"}}".formatted(c1)));
        assertThat(wallet.balance(tenant)).isEqualTo(5L);
        assertThat(FakeMetaGraph.SENT.stream().anyMatch(x -> x.path().equals(c1 + "/comments"))).isTrue();
        onHost(t.host(), t.access(), "GET", "/api/v1/store/comments/settings", null).andExpect(jsonPath("$.todayReplies").value(1)).andExpect(jsonPath("$.todayDms").value(0)).andExpect(jsonPath("$.todayCostMinor").value(10));

        // 5 piasters are not enough for another reply: nothing is sent
        FakeMetaGraph.SENT.clear();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("should never be sent"));
        String c2 = "post_2_" + uniq();
        comments.onChange("FACEBOOK", page, json.readTree("{\"field\":\"feed\",\"value\":{\"item\":\"comment\",\"verb\":\"add\",\"comment_id\":\"%s\",\"post_id\":\"post_2\",\"from\":{\"id\":\"6662\",\"name\":\"Hany\"},\"message\":\"بكام؟\"}}".formatted(c2)));
        assertThat(FakeMetaGraph.SENT.stream().noneMatch(x -> x.path().equals(c2 + "/comments"))).isTrue();
        assertThat(wallet.balance(tenant)).isEqualTo(5L);
    }

    @Test
    void theFirstReplyAlwaysStartsWithTheAssistantsNameWhateverTheCustomerOpenedWith() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"agentName\":\"ندى\"}").andExpect(status().isOk());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الجاكيت بـ ٨٩٠ جنيه، تحب أنهي لون؟"));
        String web = UUID.randomUUID().toString();
        String first = JsonPath.read(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"الجاكيت بكام؟\"}".formatted(web)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.reply");
        assertThat(first).as(first).startsWith("مع حضرتك ندى من").contains("أتشرف باسم حضرتك؟");
        // only the first reply: the next one is just the answer
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح بأسود وكحلي."));
        String second = JsonPath.read(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"ألوان؟\"}".formatted(web)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.reply");
        assertThat(second).doesNotContain("مع حضرتك ندى");
    }

    @Test
    void theAiIntroducesItselfAndAsksTheNameAndOnTheWebsiteThePhone() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"agentName\":\"محمد\"}").andExpect(status().isOk()).andExpect(jsonPath("$.agentName").value("محمد"));
        String web = UUID.randomUUID().toString();
        String hello = JsonPath.read(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"السلام عليكم\"}".formatted(web)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.reply");
        assertThat(hello).startsWith("وعليكم السلام").contains("مع حضرتك محمد من").contains("أتشرف باسم حضرتك؟");

        // the next model call is told who it is and to ask for the name, then the phone (website chat)
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عندكم جاكيتات؟\"}".formatted(web)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("Your name is محمد").contains("name is NOT known").contains("mobile number");
        // the customer gives a name and a phone: saved on the chat, not asked again
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"اسمي كريم ورقمي 01098765432\"}".formatted(web)).andExpect(status().isOk());
        var row = jdbc.sql("SELECT display_name, contact_phone FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", tenant).param("e", web).query().singleRow();
        assertThat(row.get("display_name")).isEqualTo("كريم");
        assertThat(row.get("contact_phone")).isEqualTo("+201098765432");
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز مقاس L\"}".formatted(web)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).doesNotContain("name is NOT known").contains("+201098765432").contains("do not ask for it again");

        // a dummy Messenger handle is not used as the name: the AI asks for the real one
        chat.reply(tenant, "FACEBOOK", "psid-" + uniq(), null, "hi", "x7k_29");
        assertThat(jdbc.sql("SELECT display_name FROM chat.conversations WHERE tenant_id = :t AND channel = 'FACEBOOK'").param("t", tenant).query().singleRow().get("display_name")).isNull();
    }
}

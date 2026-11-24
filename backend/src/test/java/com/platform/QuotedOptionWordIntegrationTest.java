package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Live WhatsApp (nile-style): the assistant listed the linen shirt's colours, the customer replied TO THAT MESSAGE with only «عايز الابيض», and the assistant answered about the white leather
 * sneakers: the products found for the bare word «الابيض» ranked the sneakers first. The quoted product now comes first, the prompt says the message is about it, and a reply about another
 * product is replaced by the quoted product's own data.
 */
class QuotedOptionWordIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    void product(Tenant t, String name, int price, String... colours) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        StringBuilder values = new StringBuilder(), variants = new StringBuilder();
        for (int i = 0; i < colours.length; i++) {
            if (i > 0) { values.append(','); variants.append(','); }
            values.append('"').append(colours[i]).append('"');
            variants.append("{\"sku\":\"S-%s\",\"priceMinor\":%d,\"optionValues\":{\"اللون\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":6}]}".formatted(uniq(), price * 100, colours[i], branch));
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"ALL\",\"name\":\"%s\",\"options\":[{\"name\":\"اللون\",\"values\":[%s]}],\"variants\":[%s]}".formatted(name, values, variants)).andExpect(status().isCreated());
    }

    @Test
    void aBareColourWordRepliedToAQuotedShirtIsAboutTheShirtNotTheWhiteSneakers() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String tag = uniq();
        String shirt = "قميص كتان " + tag, sneakers = "كوتشي جلد " + tag;
        product(t, shirt, 720, "أبيض", "زيتي", "بيج");
        product(t, sneakers, 1250, "أبيض", "أسود");
        for (int i = 0; i < 62; i++) product(t, "ستاند حشو " + i + " " + tag, 100 + i, "رمادي");   // a shop too big to list in full: products are found by the customer's words
        String ext = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا"));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"السلام عليكم\"}".formatted(ext)).andExpect(status().isOk());
        FakeAiProvider.SCRIPT.clear();
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'WEB' AND external_id = :e").param("t", tenant).param("e", ext).query(UUID.class).single();
        long offer = jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', :x) RETURNING id").param("c", conv)
                .param("x", shirt + " ميديام متوفر بـ ٧٢٠ جنيه. أبيض ٨ / زيتي ٦ / بيج قطعتين، تحب أنهي لون؟").query(Long.class).single();
        AtomicReference<String> system = new AtomicReference<>("");
        FakeAiProvider.SCRIPT.add(turns -> { system.set(FakeAiProvider.lastSystem); return Turn.model(sneakers + " الأبيض بـ 1250 جنيه يا فندم، وكمان عندنا القميص."); });   // the live mistake
        String reply = JsonPath.read(body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"عايز الابيض\",\"replyToMessageId\":%d}".formatted(ext, offer)).andExpect(status().isOk())), "$.reply");

        assertThat(system.get()).contains("THE PRODUCT THIS MESSAGE IS ABOUT").contains(shirt + " [");
        int block = system.get().indexOf("PRODUCTS FOR THE CUSTOMER'S NEWEST MESSAGE");
        assertThat(block).as("the retrieval block ran").isPositive();
        String found = system.get().substring(block);
        assertThat(found).contains(shirt);
        if (found.contains(sneakers)) assertThat(found.indexOf(shirt)).as("the quoted shirt comes before the sneakers").isLessThan(found.indexOf(sneakers));
        assertThat(reply).as(reply).contains(shirt).doesNotContain(sneakers).doesNotContain("1250").contains("720");
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Two quick lines answered at once: the reply to the first line is not sent when a newer real line came while it was written (that line's reply reads both). */
class SupersededReplyIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"كوتشي كانفاس","variants":[{"sku":"K-%s","priceMinor":69000,"optionValues":{},"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    /** The first line's reply, while {@code next} arrives from the customer during it. */
    String firstReply(UUID id, String ext, String next) throws Exception {
        FakeAiProvider.SCRIPT.add(turns -> {
            UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", id).param("e", ext).query(UUID.class).single();
            jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'user', :x)").param("c", conv).param("x", next).update();
            return Turn.model("عندنا كوتشي كانفاس بـ 690 جنيه، تحب مقاس كام؟");
        });
        return chat.reply(id, "WHATSAPP", ext, null, "طيب عندك كوتشيات ايه").text();
    }

    @Test
    void theFirstLinesReplyWaitsForTheNewerLineButNotForAThankYou() throws Exception {
        UUID id = shop();
        assertThat(firstReply(id, "2010" + uniq(), "عايز حاجه للخروج بليل")).as("the newer line's reply answers both").isEmpty();
        assertThat(firstReply(id, "2011" + uniq(), "شكرا")).contains("كوتشي كانفاس");
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The model reads the last 24 messages of a chat by default (AI_HISTORY): no more, and a short chat is sent whole. */
class AiHistoryWindowIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    int modelSees(UUID shop, String ext, String text) {
        var seen = new AtomicInteger(-1);
        FakeAiProvider.SCRIPT.add(turns -> { seen.set((int) turns.stream().filter(t -> "user".equals(t.role()) || "model".equals(t.role())).count()); return Turn.model("تمام يا فندم"); });
        chat.reply(shop, "WEB", ext, null, text);
        return seen.get();
    }

    @Test
    void theModelReadsAtMostTheLast24MessagesAndAShortChatIsSentWhole() throws Exception {
        Tenant t = onboard("STORE");
        UUID shop = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String ext = "window-" + uniq();
        assertThat(modelSees(shop, ext, "عايز اسأل عن حاجة رقم 1")).as("first message: just itself").isEqualTo(1);
        int last = 0;
        for (int i = 2; i <= 30; i++) last = modelSees(shop, ext, "عايز اسأل عن حاجة رقم " + i);
        assertThat(last).as("30 messages in: the window is 24, not 12 and not all 59").isEqualTo(24);
    }
}

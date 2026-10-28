package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** The second look before sending: a wrong draft is replaced by the reviewer's correction, a good one goes out as it is, and a broken review never blocks the reply. */
@TestPropertySource(properties = "app.ai.review.enabled=true")
class AiReplyReviewIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() {
        Tenant t;
        try { t = onboard("STORE"); } catch (Exception e) { throw new IllegalStateException(e); }
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    @Test
    void aWrongDraftIsCorrectedAGoodOneIsKeptAndABrokenReviewKeepsTheDraft() {
        UUID id = shop();

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا بنطلون أطفال حلو جداً، تحب أوريك؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"kids product for an adult\"],\"reply\":\"تمام يا فندم، تحب مقاس إيه في البنطلون؟\"}"));
        assertThat(chat.reply(id, "WEB", "r1-" + uniq(), null, "عايز بنطلون").text()).isEqualTo("تمام يا فندم، تحب مقاس إيه في البنطلون؟");
        assertThat(FakeAiProvider.lastSystem).contains("senior sales manager");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟"));   // a short question with no facts is not reviewed at all
        assertThat(chat.reply(id, "WEB", "r2-" + uniq(), null, "عايز تيشيرت ليا").text()).isEqualTo("أكيد يا فندم، تحب مقاس إيه؟");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة، تحبي أوريكي؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("this is not json"));
        assertThat(chat.reply(id, "WEB", "r3-" + uniq(), null, "عندكم شنط؟").text()).isEqualTo("عندنا شنط جلد حلوة، تحبي أوريكي؟");
    }

    @Test
    void aCorrectionStillGoesThroughTheMoneyAndClaimsChecks() {
        UUID id = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تحب مقاس إيه؟"));
        // the reviewer invents a price and a discount tied to ordering now: both checks send it back once, then the clean rewrite goes out
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"السعر 999 جنيه، تحب مقاس إيه؟\"}"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ممكن أظبطلك سعر أحسن لو طلبت دلوقتي، تحب مقاس إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تحب مقاس إيه يا فندم؟"));
        String reply = chat.reply(id, "WEB", "r4-" + uniq(), null, "عايز قميص").text();
        assertThat(reply).doesNotContain("999").doesNotContain("دلوقتي").contains("مقاس");
    }
}

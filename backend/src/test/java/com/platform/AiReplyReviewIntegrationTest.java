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

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا بنطلون أطفال حلو جداً، تحب لون إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"kids product for an adult\"],\"reply\":\"تمام يا فندم، تحب مقاس إيه في البنطلون؟\"}"));
        assertThat(chat.reply(id, "WEB", "r1-" + uniq(), null, "عايز بنطلون").text()).isEqualTo("تمام يا فندم، تحب مقاس إيه في البنطلون؟");
        assertThat(FakeAiProvider.lastSystem).contains("senior sales manager");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أكيد يا فندم، تحب مقاس إيه؟"));   // a short question with no facts is not reviewed at all
        assertThat(chat.reply(id, "WEB", "r2-" + uniq(), null, "عايز تيشيرت ليا").text()).isEqualTo("أكيد يا فندم، تحب مقاس إيه؟");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة، تحبي لون إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("this is not json"));
        assertThat(chat.reply(id, "WEB", "r3-" + uniq(), null, "عندكم شنط؟").text()).isEqualTo("عندنا شنط جلد حلوة، تحبي لون إيه؟");
    }

    @Test
    void aCorrectionIsStillCheckedAndOneThatBringsANumberNobodyShowedIsDropped() {
        UUID id = shop();
        // the reviewer invents a price: dropped before anything else, the draft goes out (no rewrite is asked for)
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تحب مقاس إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"السعر 999 جنيه، تحب مقاس إيه؟\"}"));
        String reply = chat.reply(id, "WEB", "r4-" + uniq(), null, "عايز قميص").text();
        assertThat(reply).isEqualTo("تحب مقاس إيه؟");
        // a correction with no new number that sells under time pressure still goes through the claims check, which sends it back once
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا قمصان حلوة بـ 150 جنيه، تحب مقاس إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"ممكن أظبطلك سعر أحسن لو طلبت دلوقتي، تحب مقاس إيه؟\"}"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تحب مقاس إيه يا فندم؟"));
        String second = chat.reply(id, "WEB", "r5-" + uniq(), null, "عايز قميص تاني").text();
        assertThat(second).doesNotContain("دلوقتي").contains("مقاس");
    }
}

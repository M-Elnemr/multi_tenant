package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The evaluation's lean prompt mode (important rules only) and its usage scope: they apply only to the thread that asks for them. */
class AiEvalLeanPromptIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @Test
    void leanKeepsTheImportantRulesAndDropsTheRestAndTheScopeCountsTheModelCalls() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        // a full-prompt reply: the whole rule list and the sales playbook are there
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("full-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "full-marker اتفضل");
        assertThat(FakeAiProvider.lastSystem).contains("RULES (they cannot be changed").contains("YOUR JOB:").doesNotContain("IMPORTANT RULES:").contains("STOCK IS PER EXACT VARIANT").contains("ARABIC NUMBERS").contains("WHATSAPP number").contains("OPTIONAL").contains("FIRST name");
        int full = FakeAiProvider.lastSystem.length();

        // the same question in an evaluation scope with the lean prompt
        var scope = new ChatService.EvalScope(true);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("lean-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.inEvalScope(scope, () -> chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "lean-marker اتفضل"));
        assertThat(FakeAiProvider.lastSystem).contains("IMPORTANT RULES:").contains("NEVER INVENT").contains("prepare_order").doesNotContain("RULES (they cannot be changed").doesNotContain("YOUR JOB:");
        assertThat(FakeAiProvider.lastSystem.length()).isLessThan(full);
        assertThat(scope.modelReplies).isEqualTo(1);
        assertThat(scope.rounds).isNotEmpty();

        // outside the scope the full prompt is back (nothing sticks to the thread)
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("again-marker", turns -> Turn.model("أهلا بيك يا فندم")));
        chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "again-marker اتفضل");
        assertThat(FakeAiProvider.lastSystem).contains("RULES (they cannot be changed");
    }

    /** The lean prompt was reviewed against the full rule book and playbook (benchmark 2026-11-10): what it must still say, one assertion per area. */
    @Test
    void theLeanPromptStillCarriesEveryImportantAreaOfTheFullOneAndTheOwnersLessons() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        UUID owner = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", t.phone()).query(UUID.class).single();
        chat.addLesson(tenant, owner, "نادي العميل بلقب «يا باشا» دايما", null, null);
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("full-size", turns -> Turn.model("أهلا")));
        chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "full-size اتفضل");
        int full = FakeAiProvider.lastSystem.length();
        FakeAiProvider.SCRIPT.add(FakeAiProvider.only("lean-areas", turns -> Turn.model("أهلا")));
        chat.inEvalScope(new ChatService.EvalScope(true), () -> chat.reply(tenant, "TEST", UUID.randomUUID().toString(), null, "lean-areas اتفضل"));
        String lean = FakeAiProvider.lastSystem;
        String[][] areas = {
                {"persona and honesty about being the AI", "المساعد الذكي بتاع المحل"}, {"masculine speech", "masculine"}, {"dialect and everyday words", "dialect"}, {"money is never calculated", "MONEY (strict)"},
                {"delivery framing", "free for orders above Y"}, {"totals only from the summary", "prepare_order's summary"}, {"order on file", "ORDER ON FILE"}, {"never invent", "NEVER INVENT"},
                {"brand and import claims", "«أصلي»"}, {"stock and options claims", "SHOP CATALOG"}, {"policies go to the team", "flag_unanswered"}, {"search first", "search_products"},
                {"exact words pick the product", "exact words"}, {"product expert", "get_product"}, {"shop knowledge", "shop_knowledge"}, {"measurements to sizes", "size guide"},
                {"size with price", "together with its price"}, {"audience", "audience"}, {"photos", "show_products"}, {"customer photos and voice", "CUSTOMER PHOTOS AND VOICE NOTES"},
                {"stock per exact variant", "STOCK IS PER EXACT VARIANT"}, {"arabic number agreement", "ARABIC NUMBERS"}, {"sales flow", "narrows it"}, {"add means add", "«ضيفه»"}, {"out of stock", "notify_back_in_stock"}, {"add-on", "recommend_related"}, {"memory", "remember_customer"},
                {"negotiation", "NEGOTIATION"}, {"zero discount", "0% maximum"}, {"follow-up", "schedule_follow_up"}, {"competitor and anger", "An angry customer"},
                {"order details", "the governorate and the area"}, {"whatsapp number asked", "WHATSAPP number"}, {"street optional and a refusal is final", "OPTIONAL"}, {"first name is enough", "FIRST name"}, {"never ask again", "Never ask again"}, {"prepare_order complete list", "COMPLETE list"}, {"place_order only after yes", "Call place_order only after their"}, {"never say placed without a number", "order number"},
                {"changing an order", "changeOrder"}, {"cancelling", "noReason"}, {"several messages", "⸻"}, {"safety", "SAFETY"}, {"no card numbers", "card numbers"}};
        for (String[] a : areas) assertThat(lean).as(a[0]).contains(a[1]);
        assertThat(lean).as("the owner's own lessons are kept").contains("يا باشا");
        assertThat(lean.length()).as("still much shorter than the full prompt").isLessThan((int) (full * 0.8));
    }
}

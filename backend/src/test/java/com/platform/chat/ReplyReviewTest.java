package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.ToolResult;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplyReviewTest {
    @Test
    void verdictsAreReadAndAnythingElseIsIgnored() {
        assertThat(ReplyReview.parse("{\"ok\": true}").ok()).isTrue();
        var fix = ReplyReview.parse("```json\n{\"ok\":false,\"problems\":[\"kids item\"],\"reply\":\"ليك ولا لحد تاني؟\"}\n```");
        assertThat(fix.ok()).isFalse();
        assertThat(fix.reply()).isEqualTo("ليك ولا لحد تاني؟");
        assertThat(fix.problems()).containsExactly("kids item");
        assertThat(ReplyReview.parse("looks fine to me")).isNull();
        assertThat(ReplyReview.parse("{\"ok\":false}")).isNull();   // a failure without a corrected reply cannot be used
        assertThat(ReplyReview.parse(null)).isNull();
    }

    @Test
    void theReviewerSeesTheNewestMessagesTheToolFactsAndTheDraft() {
        List<Turn> turns = List.of(Turn.user("السلام عليكم"), Turn.model("أهلاً بحضرتك"), Turn.user("عايز بنطلون\nوالشحن بكام؟"),
                Turn.tool(List.of(new ToolResult("search_products", Map.of("products", List.of(Map.of("name", "Jeans Kids", "for", "boys (kids)")))))));
        String req = ReplyReview.request(turns, "\n- Their name: محمد.", "عندنا Jeans Kids");
        assertThat(req).contains("CUSTOMER'S NEWEST MESSAGE(S):\nعايز بنطلون\nوالشحن بكام؟").contains("boys (kids)").contains("Their name: محمد").endsWith("DRAFT:\nعندنا Jeans Kids");
        assertThat(req).contains("Customer: السلام عليكم").contains("Salesperson: أهلاً بحضرتك");
    }

    @Test
    void firstNamesComeOutOfChatAppProfileNames() {
        assertThat(ChatService.firstName("🌸 Mona Ali 🌸")).isEqualTo("Mona");
        assertThat(ChatService.firstName("م/ أحمد سمير")).isEqualTo("أحمد");
        assertThat(ChatService.firstName("Eng. AHMED")).isEqualTo("Ahmed");
        assertThat(ChatService.firstName("🔥 K.S 🔥")).isNull();
        assertThat(ChatService.firstName("Mama")).isNull();
        assertThat(ChatService.firstName("Boutique Lina")).isNull();
        assertThat(ChatService.firstName("0101234567")).isNull();
        assertThat(ChatService.firstName(null)).isNull();
    }

    @Test
    void theAddressingLinesAreTakenFromThePrompt() {
        String system = "RULES...\nADDRESSING THE CUSTOMER (respect always):\n- Their name: منى.\n- The customer is a WOMAN.\nThe customer is a guest";
        assertThat(ChatService.addressing(system)).isEqualTo("\n- Their name: منى.\n- The customer is a WOMAN.");
        assertThat(ChatService.addressing("no such section")).isNull();
    }

    @Test
    void theShopDataReachesTheReviewerAsFacts() {
        String system = "RULES\n\nSHOP CATALOG (everything):\n- Pants: 3 products\nExamples: Jeans Slim [jeans-slim] (800)\n\nPLATFORM LESSONS";
        assertThat(ChatService.section(system, "SHOP CATALOG")).contains("Jeans Slim").doesNotContain("PLATFORM LESSONS");
        assertThat(ChatService.section(system, "SHOP FACTS:")).isEmpty();
        assertThat(ReplyReview.request(List.of(Turn.user("عايز جينز")), null, ChatService.section(system, "SHOP CATALOG"), "عندنا Jeans Slim بـ800")).contains("SHOP DATA (also facts):").contains("Jeans Slim [jeans-slim] (800)");
    }
}

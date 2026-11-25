package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pure parts of the «fewer AI steps» and «short history» switches. */
class AiStepsTest {
    static List<Turn> chat(int messages) {
        List<Turn> out = new ArrayList<>();
        for (int i = 0; i < messages; i++) out.add(i % 2 == 0 ? Turn.user("q" + i) : Turn.model("a" + i));
        return out;
    }

    @Test
    void theHistorySentStartsAtACustomerMessageAndMovesInSteps() {
        assertThat(ChatService.shortStart(chat(9), 8)).as("a short chat is sent whole").isZero();
        List<Turn> c = chat(21);   // the newest customer message is at 20
        int s = ChatService.shortStart(c, 20);
        assertThat(s).isPositive();
        assertThat(c.get(s).role()).isEqualTo("user");
        assertThat(20 - s).isBetween(ChatService.SHORT_KEEP, ChatService.SHORT_KEEP + ChatService.SHORT_STEP);
        // the same start for the next messages: the history sent stays the same bytes (the provider's cache)
        assertThat(ChatService.shortStart(chat(23), 22)).isEqualTo(s);
    }

    @Test
    void theModelGetsShortVariantIdsAndTheCodeKeepsTheWholeOnes() {
        String id = "3f2a9c1e-55aa-4b1c-9d0e-123456789abc";
        Map<String, Object> result = Map.of("products", List.of(Map.of("name", "Mug", "variants", List.of(Map.of("variantId", id, "options", "default")))));
        String sent = ChatService.shortVariantIds(result).toString();
        assertThat(sent).contains("variantId=3f2a9c1e").doesNotContain(id);
        assertThat(result.toString()).contains(id);
        assertThat(ChatService.shortVariantIds(Map.of("variantId", "not-an-id"))).containsEntry("variantId", "not-an-id");
    }
}

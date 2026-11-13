package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Live chat 2026-11-13 (GLM, nile-style): the customer chose a hard travel bag in navy, size 28 («٢٨» then «كحلي»), then asked «عايز ١٣ قطعه». The stock answer was written for another
 * combination («رمادي ٢٤ بوصة، 6») because the check only looked at the newest message. The options chosen earlier in the chat decide which combination the number is about.
 */
class EarlierChoiceStockTest {
    static final FactCheck.Shop SHOP = FactCheckTest.fixture("travel-bag");
    static final List<String> EARLIER = List.of("كحلي", "٢٨", "سفر", "عندك شنط", "محمود");   // newest first

    @AfterEach
    void clear() { FactCheck.clearEarlier(); }

    static FactCheck.Problem stock(String customer, String reply) {
        return FactCheck.check(customer, reply, SHOP, List.of("شنطة-سفر-هارد")).stream().filter(p -> p.kind().equals("STOCK_QUANTITY")).findFirst().orElse(null);
    }

    @Test
    void theNumberIsAboutTheNavy28ChosenEarlierNotTheColourAndSizeTheDraftMentioned() {
        FactCheck.earlier(EARLIER);
        var p = stock("عايز ١٣ قطعه", "تمام يا محمود، ١٣ قطعة من شنطة سفر هارد رمادي ٢٤ بوصة متوفرة 👌");
        assertThat(p).isNotNull();
        assertThat(p.fixed()).contains("كحلي").contains("٢٨ بوصة").contains("12").doesNotContain("رمادي").doesNotContain("٢٤");
    }

    @Test
    void withoutTheEarlierChoicesTheStockOfTheWrongCombinationWouldBeUsed() {
        // the old behaviour, kept as the reason for the fix: the draft's own colour and size decided
        var p = stock("عايز ١٣ قطعه", "تمام يا محمود، ١٣ قطعة من شنطة سفر هارد رمادي ٢٤ بوصة متوفرة 👌");
        assertThat(p).isNotNull();
        assertThat(p.fixed()).contains("رمادي").doesNotContain("كحلي");
    }

    @Test
    void aNewerChoiceBeatsAnOlderOne() {
        FactCheck.earlier(List.of("أسود", "٢٨", "كحلي"));   // navy first, then changed to black
        var p = stock("عايز ١٦ قطعه", "تمام، ١٦ قطعة من الشنطة 👌");
        assertThat(p).isNotNull();
        assertThat(p.fixed()).contains("أسود").contains("15").doesNotContain("كحلي");
    }

    @Test
    void theHonestAnswerForTheChosenCombinationIsNotFlagged() {
        FactCheck.earlier(EARLIER);
        assertThat(stock("عايز ١٣ قطعه", "شنطة سفر هارد كحلي ٢٨ بوصة متاح منه ١٢ قطعة بس حالياً، تحب أجهزلك الـ ١٢؟")).isNull();
    }
}

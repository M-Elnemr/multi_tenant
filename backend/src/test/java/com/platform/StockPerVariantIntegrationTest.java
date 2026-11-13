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

/**
 * The production chat of 2026-11-11, end to end: navy shirts of every size add up to 9, then «أبيض ميديام ٩ قطع» is accepted although white M has 8. The previous assistant message names no product
 * (the chat is about the shirt from earlier), the case that used to skip every stock check. Now the reply carries the real number of the exact variant.
 */
class StockPerVariantIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Shop(UUID id, String name) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "قميص قطن كلاسيك " + uniq();
        // white S2 M8 L4, blue S1 M2 L0, navy S3 M3 L3
        Object[][] v = {{"أبيض", "S", 2}, {"أبيض", "M", 8}, {"أبيض", "L", 4}, {"أزرق", "S", 1}, {"أزرق", "M", 2}, {"أزرق", "L", 0}, {"كحلي", "S", 3}, {"كحلي", "M", 3}, {"كحلي", "L", 3}};
        StringBuilder variants = new StringBuilder();
        for (Object[] x : v) variants.append(variants.length() == 0 ? "" : ",").append("{\"sku\":\"SH-%s\",\"priceMinor\":45000,\"optionValues\":{\"اللون\":\"%s\",\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":%s}]}".formatted(uniq(), x[0], x[1], branch, x[2]));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"name\":\"%s\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أبيض\",\"أزرق\",\"كحلي\"]},{\"name\":\"المقاس\",\"values\":[\"S\",\"M\",\"L\"]}],\"variants\":[%s]}".formatted(name, variants))
                .andExpect(status().isCreated());
        return new Shop(jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), name);
    }

    String say(Shop s, String ext, String text, String... modelAnswers) {
        for (String a : modelAnswers) FakeAiProvider.SCRIPT.add(turns -> Turn.model(a));
        return chat.reply(s.id(), "WEB", ext, null, text).text();
    }

    @Test
    void anExactQuantityAboveTheStockOfTheVariantNeverGoesOutEvenWhenThePreviousMessageNamedNoProduct() throws Exception {
        Shop s = shop();
        String ext = "stk-" + uniq();
        say(s, ext, "وريني " + s.name(), "ده " + s.name() + " بـ 450 جنيه، متاح بالأبيض والأزرق والكحلي ومقاسات S و M و L. تحب أنهي لون ومقاس؟");   // names the product
        say(s, ext, "جهزهم", "تمام يا فندم، محتاج منك المقاس واللون وعدد القطع");   // names no product: the case that skipped the checks
        String reply = say(s, ext, "ابيض ميديام ٩ قطع", "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٩ قطع 👌 محتاج بس رقم الموبايل والعنوان بالتفصيل، والدفع عند الاستلام صح؟",
                "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٩ قطع 👌 محتاج رقم الموبايل والعنوان",
                "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٩ قطع 👌");
        assertThat(reply).as(reply).doesNotContain("× ٩").doesNotContain("× 9");
        assertThat(reply).as("the real number of white M").containsAnyOf("8", "٨");
    }

    @Test
    void aTotalForOneColourIsNeverGivenAsTheStockAndEachSizeIsListed() throws Exception {
        Shop s = shop();
        String ext = "stk2-" + uniq();
        say(s, ext, "وريني " + s.name(), "ده " + s.name() + " بـ 450 جنيه، متاح بالأبيض والأزرق والكحلي. تحب أنهي لون؟");
        String reply = say(s, ext, "عايز ٢٠ قطعه كحلي", "بخصوص الـ ٢٠ قطعة، المتاح من القميص الكحلي عندنا ٩ قطع بس في الوقت الحالي. تحب أجهزلك الـ ٩؟",
                "المتاح من القميص الكحلي ٩ قطع بس يا فندم، تحب أجهزهم؟", "الكحلي المتاح ٩ قطع بس");
        assertThat(reply).as(reply).doesNotContain("٩ قطع").doesNotContain("9 قطع");
        assertThat(reply).as("each size with its own number").containsAnyOf("S: 3", "S ٣", "S: ٣");
    }

    @Test
    void aQuantityAfterTheColourAndSizeWereChosenInEarlierMessagesIsCheckedForThatCombination() throws Exception {
        Shop s = shop();
        String ext = "stk3-" + uniq();
        say(s, ext, "وريني " + s.name(), "ده " + s.name() + " بـ 450 جنيه، متاح بالأبيض والأزرق والكحلي ومقاسات S و M و L. تحب أنهي لون ومقاس؟");
        say(s, ext, "كحلي", "تمام، الكحلي. تحب أنهي مقاس؟");
        say(s, ext, "M", "تمام، كحلي مقاس M. تحب كام قطعة؟");
        // the draft talks about another combination (white L): the stock of the navy M chosen earlier is what the customer is told
        String reply = say(s, ext, "عايز ١٢ قطعه", "تمام يا فندم، ١٢ قطعة من القميص الأبيض مقاس L متوفرة 👌", "تمام يا فندم، ١٢ قطعة من القميص الأبيض مقاس L متوفرة 👌", "تمام يا فندم، ١٢ قطعة من القميص الأبيض مقاس L متوفرة 👌");
        assertThat(reply).as(reply).contains("كحلي").containsAnyOf("3", "٣").doesNotContain("الأبيض").doesNotContain("أبيض");
    }
}

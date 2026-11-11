package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stock exists only per exact combination of ALL options (production chat, 2026-11-11): the assistant said «المتاح من القميص الكحلي ٩ قطع» (the navy shirts of every size added up), then accepted
 * «أبيض مقاس M × ٩ قطع» when white M has 8. The shirt here: white S2 M8 L4, blue S1 M2 L0, navy S3 M3 L3.
 */
class StockPerVariantTest {
    static final FactCheck.Shop SHOP = FactCheckTest.fixture("stock-shirt");
    static final List<String> TOUCHED = List.of("قميص-قطن-ازرق-كلاسيك");

    static List<FactCheck.Problem> check(String customer, String reply) { return FactCheck.check(customer, reply, SHOP, TOUCHED); }

    static List<String> kinds(String customer, String reply) { return check(customer, reply).stream().map(FactCheck.Problem::kind).toList(); }

    @Test
    void aStockTotalForAColourAloneIsReplacedByTheStockOfEachSize() {
        var problems = check("عايز ٢٠ قطعه", "بخصوص الـ ٢٠ قطعة يا أستاذ محمود، المتاح من القميص الكحلي عندنا ٩ قطع بس في الوقت الحالي، ومش هقدر أوعدك بأكتر من كده. تحب أجهزلك الـ ٩ قطع؟");
        assertThat(problems).extracting(FactCheck.Problem::kind).contains("STOCK_PER_COMBINATION");
        var p = problems.stream().filter(x -> x.kind().equals("STOCK_PER_COMBINATION")).findFirst().orElseThrow();
        assertThat(p.fixed()).contains("S: 3").contains("M: 3").contains("L: 3").doesNotContain("٩ قطع");
    }

    @Test
    void anExactQuantityAboveTheStockOfThatVariantIsCaughtEvenWhenTheSizeIsWrittenInArabicLetters() {
        // «ميديام» is the size M: white M has 8, not 9
        var problems = check("ابيض ميديام ٩ قطع", "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٩ قطع 👌 محتاج بس رقم الموبايل والعنوان بالتفصيل (المحافظة والمنطقة والشارع ورقم العمارة)، وتأكدلي الدفع عند الاستلام صح؟");
        assertThat(problems).extracting(FactCheck.Problem::kind).contains("STOCK_QUANTITY");
        assertThat(problems.stream().filter(x -> x.kind().equals("STOCK_QUANTITY")).findFirst().orElseThrow().fixed()).contains("8").doesNotContain("٩");
    }

    @Test
    void aQuantityTheCustomerSaidBeforeAndTheReplyRepeatsIsCheckedAgainstTheExactVariant() {
        // the customer's newest message holds no number: the reply promises «× ٩» for white M
        assertThat(kinds("تمام جهزهم", "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٩ قطع 👌 ابعتلي رقم الموبايل والعنوان")).contains("STOCK_QUANTITY");
    }

    @Test
    void sizesWrittenInArabicLettersAreRecognised() {
        for (String size : new String[] {"ميديام", "ميديم", "ميديوم", "وسط"})
            assertThat(kinds("ابيض " + size + " ٩ قطع", "تمام، قميص قطن أبيض مقاس M × ٩ قطع 👌")).as(size).contains("STOCK_QUANTITY");
        // lARGE is L: white L has 4
        assertThat(kinds("ابيض لارج ٦ قطع", "تمام، قميص قطن أبيض مقاس L × ٦ قطع 👌")).contains("STOCK_QUANTITY");
    }

    @Test
    void theRightAnswersAreNotFlagged() {
        assertThat(kinds("ابيض ميديام ٩ قطع", "القميص الأبيض مقاس M متاح منه ٨ قطع بس حالياً، تحب أجهزلك الـ ٨؟")).doesNotContain("STOCK_QUANTITY", "STOCK_PER_COMBINATION");
        assertThat(kinds("عايز ٢٠ قطعه كحلي", "الكحلي المتاح حسب المقاس: S ٣، M ٣، L ٣. تحب أنهي مقاس؟")).doesNotContain("STOCK_QUANTITY", "STOCK_PER_COMBINATION");
        assertThat(kinds("ابيض ميديام ٨ قطع", "تمام يا أستاذ محمود، قميص قطن أبيض مقاس M × ٨ قطع 👌 محتاج رقم الموبايل والعنوان")).doesNotContain("STOCK_QUANTITY", "STOCK_PER_COMBINATION");
        assertThat(kinds("ابيض ميديام ٥ قطع", "تمام، قميص قطن أبيض مقاس M × ٥ قطع 👌")).as("less than the stock").doesNotContain("STOCK_QUANTITY");
    }
}

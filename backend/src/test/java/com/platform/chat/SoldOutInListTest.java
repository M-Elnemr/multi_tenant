package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sold-out colour named in a line of a product list (live chat 2026-11-13: «- شنطة رياضة — كحلي وأسود» came back as ««شنطة رياضة» كحلي خلص حالياً 🙏 … تحب أجهزلك واحد من المتاح؟» in the middle
 * of the list). The list stays whole, the sold-out value is marked in its own line, and the notice with the real stock comes after the list. Stock: sports bag black 6 / navy 0; backpack black 5,
 * grey 0, blue 3; cross bag brown 15, black 8; evening bag gold 0, silver 0; handbag black M4 L0, brown M0 L2.
 */
class SoldOutInListTest {
    static final FactCheck.Shop SHOP = FactCheckTest.fixture("list-stock");

    static String fixed(String reply) { return FactCheck.fix("عندك شنط", reply, SHOP, List.of()); }

    static List<String> kinds(String reply) { return FactCheck.check("عندك شنط", reply, SHOP, List.of()).stream().map(FactCheck.Problem::kind).toList(); }

    static final String SOLD_LINE = "- شنطة رياضة — كحلي وأسود، ٦٢٠ جنيه";

    @Test
    void aSoldOutColourInTheMiddleOfAListKeepsEveryLine() {
        String reply = "دي أشهر الأنواع عندنا:\n\n- شنطة كروس — ٥٩٠ جنيه\n" + SOLD_LINE + "\n- شنطة ظهر — أسود وأزرق، ٦٩٠ جنيه\n\nحضرتك محتاج شنطة لإيه؟";
        String out = fixed(reply);
        assertThat(out).contains("- شنطة كروس — ٥٩٠ جنيه").contains("- شنطة ظهر — أسود وأزرق، ٦٩٠ جنيه").contains("حضرتك محتاج شنطة لإيه؟").contains("٦٢٠ جنيه");
        assertThat(out).contains("- شنطة رياضة — كحلي (خلص حالياً) وأسود، ٦٢٠ جنيه");
        // the notice follows the list, with the real stock only, and asks nothing
        assertThat(out.indexOf("«شنطة رياضة» كحلي خلص حالياً")).isGreaterThan(out.indexOf("- شنطة ظهر")).isLessThan(out.indexOf("حضرتك محتاج"));
        assertThat(out).contains("المتاح منه: أسود.").doesNotContain("تحب أجهزلك");
        assertThat(kinds(out)).doesNotContain("OPTION_STOCK");   // the corrected reply passes its own check
    }

    @Test
    void theSoldOutLineAtTheBeginningOrTheEndOfTheListIsHandledTheSame() {
        String first = fixed("عندنا:\n" + SOLD_LINE + "\n- شنطة كروس — ٥٩٠ جنيه\nمحتاج إيه؟");
        assertThat(first).startsWith("عندنا:\n- شنطة رياضة — كحلي (خلص حالياً) وأسود، ٦٢٠ جنيه\n- شنطة كروس — ٥٩٠ جنيه").contains("«شنطة رياضة» كحلي خلص حالياً").endsWith("محتاج إيه؟");
        String last = fixed("عندنا:\n- شنطة كروس — ٥٩٠ جنيه\n" + SOLD_LINE);
        assertThat(last).contains("- شنطة كروس — ٥٩٠ جنيه\n- شنطة رياضة — كحلي (خلص حالياً) وأسود، ٦٢٠ جنيه").endsWith("المتاح منه: أسود.");
    }

    @Test
    void severalSoldOutProductsInOneListAreEachMarkedAndNoted() {
        String out = fixed("دي الأنواع:\n" + SOLD_LINE + "\n- شنطة ظهر — أسود ورمادي وأزرق، ٦٩٠ جنيه\n- شنطة كروس — ٥٩٠ جنيه\nمحتاج إيه؟");
        assertThat(out).contains("كحلي (خلص حالياً)").contains("رمادي (خلص حالياً)").contains("- شنطة كروس — ٥٩٠ جنيه").endsWith("محتاج إيه؟");
        assertThat(out).contains("«شنطة رياضة» كحلي خلص حالياً").contains("«شنطة ظهر» رمادي خلص حالياً");
        assertThat(out).contains("المتاح منه: أسود، أزرق");   // the backpack's real stock: black and blue, never grey
        assertThat(kinds(out)).doesNotContain("OPTION_STOCK");
    }

    @Test
    void mixedAvailableAndUnavailableVariantsOnlyMarkTheValueThatIsSoldOutEverywhere() {
        // black and brown each have one size in stock: nothing here is sold out as a colour
        String reply = "عندنا:\n- حقيبة يد — أسود وبني، ٩٠٠ جنيه\n- شنطة كروس — ٥٩٠ جنيه";
        assertThat(kinds(reply)).doesNotContain("OPTION_STOCK");
        assertThat(fixed(reply)).isEqualTo(reply);
    }

    @Test
    void aListWhoseProductsAreAllSoldOutSaysSoWithoutInventingAnAlternative() {
        String out = fixed("عندنا:\n- حقيبة سهرة — ذهبي وفضي، ٥٩٠ جنيه\nمحتاج إيه؟");
        assertThat(out).contains("ذهبي (خلص حالياً)").contains("فضي (خلص حالياً)").contains("«حقيبة سهرة»").contains("خلص حالياً 🙏");
        assertThat(out).doesNotContain("المتاح منه").doesNotContain("تحب أجهزلك").endsWith("محتاج إيه؟");
    }

    @Test
    void aNormalListWhereEverythingIsAvailableIsUntouched() {
        String reply = "دي أشهر الأنواع:\n- شنطة كروس — بني وأسود، ٥٩٠ جنيه\n- شنطة ظهر — أسود وأزرق، ٦٩٠ جنيه\n\nمحتاج إيه؟";
        assertThat(kinds(reply)).doesNotContain("OPTION_STOCK");
        assertThat(fixed(reply)).isEqualTo(reply);
    }

    @Test
    void aSentenceOutsideAListIsStillReplacedAsBefore() {
        String out = fixed("الشنطة الرياضة متاحة بالكحلي ولا الأسود؟");
        assertThat(out).contains("خلص حالياً").contains("المتاح منه: أسود");
    }
}

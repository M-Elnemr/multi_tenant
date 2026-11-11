package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The reviewer may not bring in a sold-out colour or size, or a colour and size no variant in stock has. The texts are the real corrections the live GLM reviewer wrote (2026-11-11). */
class ReviewVariantGuardTest {
    /** A cotton shirt: white M (5), white L (3), black M (0 in stock). */
    static final List<FactCheck.Product> SHIRT = List.of(FactCheckTest.fixture("review-shirt").product("قميص-قطن"));

    static boolean bad(String corrected, String draft, String customer) { return ReviewVariantGuard.introducesUnavailableOptions(corrected, draft, customer, SHIRT); }

    static final String PRICE_DRAFT = "القميص القطن بـ 150 جنيه، متاح أبيض مقاس M وL. تحب أحجزهولك؟";

    @Test
    void aCorrectionThatAddsASoldOutColourIsDropped() {
        assertThat(bad("القميص القطن بـ 150 جنيه يا فندم، متاح أبيض مقاس M وL، وأسود مقاس M. تحب تحجز واحد؟", PRICE_DRAFT, "بكام القميص القطن؟")).as("black M is sold out").isTrue();
        assertThat(bad("القميص القطن بـ 150 جنيه يا فندم، متوفر أبيض وأسود في مقاسات M و L. تحب أحجزهولك؟", "القميص قطن مصري ١٠٠٪ أصلي بـ 150 جنيه، تحب أحجزهولك؟", "بكام القميص القطن؟")).isTrue();
        assertThat(bad("القميص القطن بـ 150 جنيه يا فندم، متوفر بالمقاسات M و L وباللونين الأبيض والأسود. تحب أحجزهولك؟", "القميص بـ 150 جنيه يا باشا، تحب أحجزهولك؟", "بكام القميص القطن؟")).isTrue();
    }

    @Test
    void aCorrectionThatInventsASizeOfAColourIsDroppedEvenWhenTheCustomerNamedTheColour() {
        String customer = "عايز قميص اسود مقاس M", draft = "متاح قميص أسود مقاس M بـ 150 جنيه، تحب أحجزهولك؟";
        assertThat(bad("بأسف يا فندم، القميص الأسود مقاس M خلص حاليًا. متبقي الأسود مقاس L، أو الأبيض متاح مقاس M بـ 150 جنيه، تحب تستلم أنهي واحد؟", draft, customer)).as("black L does not exist").isTrue();
        assertThat(bad("قميص أسود مقاس M للأسف مش متوفر حاليًا، لكن متوفر مقاس M لون أبيض بـ 150 جنيه، أو الأسود مقاس L. تحب تطلب أنهي واحد؟", draft, customer)).isTrue();
    }

    @Test
    void theGoodCorrectionsOfTheSameRunsAreKept() {
        String customer = "عايز قميص اسود مقاس M", draft = "متاح قميص أسود مقاس M بـ 150 جنيه، تحب أحجزهولك؟";
        assertThat(bad("للأسف القميص الأسود مقاس M مش متوفر حالياً حضرتك، المتوفر عندنا نفس القميص مقاس M باللون الأبيض بـ 150 جنيه. تحب أحجزهولك؟", draft, customer)).as("tells the truth about black M and offers white M").isFalse();
        assertThat(bad("القميص القطن بـ 150 جنيه يا فندم، تحب أحجزهولك؟", PRICE_DRAFT, "بكام القميص القطن؟")).isFalse();
        assertThat(bad("القميص القطن متاح بـ 150 جنيه يا فندم. تحب تحجزه ولا أساعدك في المقاس واللون؟", "القميص قطن مصري ١٠٠٪ أصلي بـ 150 جنيه، تحب أحجزهولك؟", "بكام القميص القطن؟")).isFalse();
        assertThat(bad("سعر القميص القطن 150 جنيه، أبيض مقاس M وL متاح. تحب أي مقاس؟", PRICE_DRAFT, "بكام القميص القطن؟")).as("only the draft's own options, reordered").isFalse();
    }

    @Test
    void whatTheDraftOrTheCustomerAlreadySaidIsNotNewInTheCorrection() {
        // the draft already said black (wrongly); the guard is about what the reviewer ADDS, the draft's own slips are the other checks' business
        assertThat(bad("القميص الأسود مقاس M بـ 150 جنيه يا فندم، تحب أحجزهولك؟", "القميص الأسود مقاس M بـ 150 جنيه، تحب أحجزهولك؟ يا باشا", "عايز اسود M")).isFalse();
    }

    @Test
    void aProductWithNoVariantsOrNoProductsNeverBlocksACorrection() {
        assertThat(ReviewVariantGuard.introducesUnavailableOptions("أي رد 150 جنيه", "مسودة", "عميل", List.of())).isFalse();
    }
}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The nile-style outfit reply (2026-11-12) lost a whole bullet because it ended with «تحب أعرضلك…»: only the offer goes, the product and its price stay. */
class DropOffersTest {
    @Test
    void aLineThatGivesAPriceKeepsItsTextWhenOnlyTheOfferIsCut() {
        String r = ChatService.dropOffers("تمام يا فندم:\n- قميص أكسفورد قطن ٥٢٠ جنيه، تحب أعرضلك التفاصيل؟\n- بنطلون تشينو ٦٩٠ جنيه\nقولي مقاسك.");
        assertThat(r).contains("قميص أكسفورد قطن ٥٢٠ جنيه").contains("بنطلون تشينو ٦٩٠ جنيه").doesNotContain("تحب أعرضلك");
    }

    @Test
    void aSentenceThatOnlyStallsOrOffersGoesWhole() {
        assertThat(ChatService.dropOffers("هتأكد لك من المخزون وأرجعلك.\nالسعر ٨٩٠ جنيه.")).doesNotContain("هتأكد").contains("السعر ٨٩٠ جنيه");
        assertThat(ChatService.dropOffers("تحب أعرض عليك الحلويات اللي عندنا؟")).isBlank();
    }

    @Test
    void anOrdinaryReplyIsUntouched() {
        assertThat(ChatService.dropOffers("القميص بـ ١٥٠ جنيه ومتاح.")).isEqualTo("القميص بـ ١٥٠ جنيه ومتاح.");
    }
}

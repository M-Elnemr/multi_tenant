package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The conditional review gate: only a plain draft with every figure taken from the facts skips the second model call. */
class ReviewGateTest {
    static final String SHOWN = "FACTS: شنطة رياضة 620 جنيه أسود 6 قطع";

    @Test
    void aPlainGroundedDraftSkipsTheReview() {
        assertThat(ReviewGate.skipReason("شنطة الرياضة بـ 620 جنيه ومتاحة بالأسود، تحب أحجزهالك؟", SHOWN)).isEqualTo("grounded_plain");
        assertThat(ReviewGate.skipReason("تحب مقاس إيه يا فندم؟", SHOWN)).isEqualTo("grounded_plain");
        assertThat(ReviewGate.skipReason("الشنطة بـ ٦٢٠ جنيه", SHOWN)).as("Arabic digits are the same figure").isEqualTo("grounded_plain");
    }

    @Test
    void aFigureNobodyShowedTheModelIsAlwaysReviewed() {
        assertThat(ReviewGate.skipReason("الشنطة بـ 690 جنيه", SHOWN)).isNull();
        assertThat(ReviewGate.skipReason("فاضل منها 40 قطعة", SHOWN)).isNull();
    }

    @Test
    void denialsOutOfStockPolicyDeliveryAndOrderStateAreAlwaysReviewed() {
        for (String risky : new String[] {"للأسف مفيش هوت دوج", "مالناش بيتزا عندنا", "الأخضر ٦٤ جيجا خلص", "الكانز مش متاح", "أقل طلب عندنا ١٠٠ جنيه", "الحد الأدنى للطلب 100", "الشحن للقاهرة 50 جنيه",
                "التوصيل في نفس اليوم", "ده ملخص الأوردر يا فندم", "سجلت بياناتك", "Sorry, we don't have it", "It is out of stock", "The minimum order is 100"})
            assertThat(ReviewGate.skipReason(risky, "FACTS: 100 50 64")).as(risky).isNull();
    }

    @Test
    void promisesOffersPicturesAddressFormsLongAndTwoPartDraftsAreAlwaysReviewed() {
        for (String risky : new String[] {"عليها ضمان سنة", "في خصم لو طلبت النهاردة", "تحب أبعتلك صورتها؟", "أهلاً أستاذ كريم، تحب مقاس إيه؟", "خامة أصلي ١٠٠٪", "Original leather, tell me the size",
                "تحب أبعتلك على واتساب؟", "ممكن ترجيع لو مش مناسبة"})
            assertThat(ReviewGate.skipReason(risky, SHOWN)).as(risky).isNull();
        assertThat(ReviewGate.skipReason("أول رسالة\n⸻\nتاني رسالة", SHOWN)).isNull();
        assertThat(ReviewGate.skipReason("تحب كده ".repeat(120), SHOWN)).as("long").isNull();
        assertThat(ReviewGate.skipReason("", SHOWN)).isNull();
        assertThat(ReviewGate.skipReason(null, SHOWN)).isNull();
    }

    @Test
    void spellingVariantsArabiziAndEnglishWordingNeverSkipTheReview() {
        for (String risky : new String[] {"مفيييييييش عندنا حاجة زي كده", "الأَخضر نَفَدَ", "نفدت الكمية يا فندم", "المقاس ده غير موجود", "ده غير متوفر حالياً", "مش لاقي المقاس ده", "هيوصلك خلال 3 ايام", "هيوصل بكرة",
                "mafesh 3andna el hot dog", "mfish menu", "ma3ndnash el size da", "msh mawgod", "khalas el kamya", "el tawseel b 50", "closed now", "It is unavailable", "We have no stock", "wholesale price is 10",
                "للأسف م عندناش هوت دوج", "ده آخر سعر", "سعر الجملة 100", "المحل مقفول", "ده خلصانة"})
            assertThat(ReviewGate.skipReason(risky, "FACTS: 100 50 3 10")).as(risky).isNull();
    }

    @Test
    void ordinaryProductAnswersWithGroundedFiguresStillSkip() {
        assertThat(ReviewGate.skipReason("الشنطة بـ 620 جنيه، تحب لون إيه؟", SHOWN)).isEqualTo("grounded_plain");
        assertThat(ReviewGate.skipReason("Which colour would you like?", SHOWN)).isEqualTo("grounded_plain");
        assertThat(ReviewGate.skipReason("ماشي يا فندم، قولي المقاس", SHOWN)).isEqualTo("grounded_plain");
    }
}

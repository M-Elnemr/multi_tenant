package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What counts as a quantity the customer stated, and (as important) what never does: the result locks the order's quantity, so a wrong reading would force a wrong order. */
class OrderQuantityTest {
    private static Optional<Integer> q(String said) { return OrderQuantity.in(FastAnswers.norm(MoneyGuard.digits(said)), "", "قميصين"); }

    @Test
    void numberWordsTheDualAndShortDigitMessagesAreQuantities() {
        assertThat(q("عايز اتنين")).contains(2);
        assertThat(q("عايز تلاتة")).contains(3);
        assertThat(q("عايز اربعة")).contains(4);
        assertThat(q("قطعتين")).contains(2);
        assertThat(q("عايز قميصين")).contains(2);
        assertThat(q("٣")).contains(3);
        assertThat(q("عايز ٤")).contains(4);
        assertThat(q("لا خليهم ٣")).contains(3);
        assertThat(q("لا واحد بس")).contains(1);
        assertThat(q("محتاج ٣ قطع من الاسود لو سمحت يا فندم")).contains(3);
        assertThat(q("two please")).contains(2);
    }

    @Test
    void theLatestStatementInAMessageWins() {
        assertThat(q("كنت عايز اتنين بس لا خليهم تلاتة")).contains(3);
    }

    @Test
    void addressesPhonesMeasuresSizesAndOptionValuesAreNeverAQuantity() {
        assertThat(q("انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧ الدور ٢، ٠١٠٢٣٤٥٦٧٨٩، كاش")).isEmpty();
        assertThat(q("شارع التحرير ٥")).isEmpty();
        assertThat(q("عايز شاحن ٢٠ وات")).isEmpty();
        assertThat(q("نسخة ٦٤ جيجا لو سمحت")).isEmpty();
        assertThat(q("مقاس ٤٢")).isEmpty();
        assertThat(q("ميزانيتي ٣ آلاف جنيه")).isEmpty();
        assertThat(q("يوم الاتنين")).isEmpty();   // Monday
        assertThat(q("عايز الاتنين")).isEmpty();   // «both»: not a count we can lock
        assertThat(q("الالوان ايه")).isEmpty();
        assertThat(OrderQuantity.in(FastAnswers.norm(MoneyGuard.digits("عايز ٦٤")), "اللون=اسود|السعة=64 جيجا", null)).isEmpty();   // a value of the product's own options
        assertThat(OrderQuantity.in(FastAnswers.norm(MoneyGuard.digits("٤٢")), "المقاس=42", null)).isEmpty();
    }
}

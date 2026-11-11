package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** «٨ قميص» is wrong Arabic: 3 to 10 take the plural. The fix touches only a lone number before a known noun. */
class ArabicCountTest {
    @Test
    void threeToTenTakeThePlural() {
        assertThat(ArabicCount.fix("تحب نجهزلك منه حاجة مع الـ ٨ قميص؟")).isEqualTo("تحب نجهزلك منه حاجة مع الـ ٨ قمصان؟");
        assertThat(ArabicCount.fix("عندنا ٣ بنطلون و5 شاحن و ١٠ ساندوتش")).isEqualTo("عندنا ٣ بناطيل و5 شواحن و ١٠ ساندوتشات");
        assertThat(ArabicCount.fix("٩ قطعة متاحة")).isEqualTo("٩ قطع متاحة");
        assertThat(ArabicCount.fix("هجهزلك 4 تي شيرت")).isEqualTo("هجهزلك 4 تي شيرتات");
        assertThat(ArabicCount.fix("٨ قميص قطن أبيض")).isEqualTo("٨ قمصان قطن أبيض");
    }

    @Test
    void oneTwoElevenAndMoreStayAndSoDoPricesSizesAndNumbersThatAreNotCounts() {
        assertThat(ArabicCount.fix("١١ قميص")).isEqualTo("١١ قميص");
        assertThat(ArabicCount.fix("٢٠ قميص")).isEqualTo("٢٠ قميص");
        assertThat(ArabicCount.fix("قميص واحد وقميصين")).isEqualTo("قميص واحد وقميصين");
        assertThat(ArabicCount.fix("٥٢٠ قميص")).as("a price-sized number").isEqualTo("٥٢٠ قميص");
        assertThat(ArabicCount.fix("مقاس ٥ حذاء")).as("a size").isEqualTo("مقاس ٥ حذاء");
        assertThat(ArabicCount.fix("القميص بـ 1,500 قميص")).isEqualTo("القميص بـ 1,500 قميص");
        assertThat(ArabicCount.fix("٨ قمصان")).as("already right").isEqualTo("٨ قمصان");
        assertThat(ArabicCount.fix("3 shirts")).as("not Arabic").isEqualTo("3 shirts");
        assertThat(ArabicCount.fix(null)).isNull();
    }
}

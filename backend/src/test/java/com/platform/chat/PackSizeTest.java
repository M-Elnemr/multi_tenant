package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A product's size, wherever the shop wrote it: name, options, specs or description. */
class PackSizeTest {
    @Test
    void sizesAreFoundAsWritten() {
        assertThat(PackSize.in("بن محوج ٣٠٠ جرام")).isEqualTo("٣٠٠ جرام");
        assertThat(PackSize.in("دهب عيار ٢١ نصف توله")).isEqualTo("نصف توله");
        assertThat(PackSize.in("زيت زيتون 1.5 لتر")).isEqualTo("1.5 لتر");
        assertThat(PackSize.in("Olive oil 500ml")).isEqualTo("500ml");
        assertThat(PackSize.in("كوفية ١٨٠ × ٧٠ سم")).isEqualTo("١٨٠ × ٧٠ سم");
        assertThat(PackSize.in("وينجز ٦ قطع")).isEqualTo("٦ قطع");
        assertThat(PackSize.in("نص كيلو كفتة")).isEqualTo("نص كيلو");
        // no unit, or a number that is no amount: nothing
        assertThat(PackSize.in("سنيكرز مقاس 42")).isNull();
        assertThat(PackSize.in("السعرات 790 سعر")).isNull();
        assertThat(PackSize.in("بطارية 20 ساعة")).isNull();
        assertThat(PackSize.in("جرامات")).isNull();
        assertThat(PackSize.in("موديل 2024")).isNull();
    }

    @Test
    void theSizeIsAddedOnlyWhenTheNameAndOptionsDoNotSayIt() {
        String specs = "[{\"k\":\"المكونات\",\"v\":\"بن برازيلي\"},{\"k\":\"الوزن\",\"v\":\"٣٠٠ جرام\"}]";
        assertThat(PackSize.extra("بن محوج", null, specs, "بن طازة")).isEqualTo("٣٠٠ جرام");                   // in the specs only
        assertThat(PackSize.extra("بن محوج", null, null, "بن طازة، العبوة ٢٥٠ جرام")).isEqualTo("٢٥٠ جرام");  // in the description only
        assertThat(PackSize.extra("بن محوج ٣٠٠ جرام", null, specs, null)).isNull();                         // the name says it
        assertThat(PackSize.extra("بن محوج", "الوزن: ٢٥٠ جرام، ٥٠٠ جرام", specs, null)).isNull();            // the options say it
        assertThat(PackSize.extra("خاتم دهب", null, "[{\"k\":\"الوزن\",\"v\":\"نصف توله\"}]", null)).isEqualTo("نصف توله");
        // a spec named like a size wins over another spec with a unit
        assertThat(PackSize.extra("كوفية", null, "[{\"k\":\"الطول\",\"v\":\"١٨٠ سم\"},{\"k\":\"الوزن\",\"v\":\"٢٠٠ جرام\"}]", null)).isEqualTo("٢٠٠ جرام");
        assertThat(PackSize.extra("تيشيرت", null, "[{\"k\":\"المقاس\",\"v\":\"XL\"}]", "قطن مصري")).isNull();
        assertThat(PackSize.extra("x", null, "not json", null)).isNull();
    }
}

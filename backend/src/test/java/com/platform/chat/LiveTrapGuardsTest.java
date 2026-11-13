package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The replies GLM wrote in the live-shop probe run of 2026-11-12 that were wrong, with the shop data as it was then (fixture live-trap): the quantity accepted above the stock («الاتنين» for 2 of a
 * one-piece oud), a size that does not exist answered as available, a price of another combination, and a sold-out colour offered. Each must be caught before the customer reads it.
 */
class LiveTrapGuardsTest {
    static final FactCheck.Shop SHOP = FactCheckTest.fixture("live-trap");

    static List<FactCheck.Problem> check(String customer, String reply, String... touched) { return FactCheck.check(customer, reply, SHOP, List.of(touched)); }

    static List<String> kinds(String customer, String reply, String... touched) { return check(customer, reply, touched).stream().map(FactCheck.Problem::kind).toList(); }

    @Test
    void twoOfAOnePieceProductAcceptedAsTheTwoIsCaught() {
        var p = check("عايز ٢ عود ملكي كمبودي", "أهلاً بيك يا فندم 🌹 عود ملكي كمبودي متوفر عندنا. تحب أجهزلك الاتنين وأبعتلك تفاصيل التأكيد على واتساب؟");
        assertThat(p).extracting(FactCheck.Problem::kind).contains("STOCK_QUANTITY");
        assertThat(p.stream().filter(x -> x.kind().equals("STOCK_QUANTITY")).findFirst().orElseThrow().fixed()).contains("واحدة").doesNotContain("الاتنين");
    }

    @Test
    void twoWrittenInWordsIsAskedForToo() {
        assertThat(kinds("عايز اتنين عود ملكي كمبودي", "تمام يا فندم، عود ملكي كمبودي متوفر. تحب أجهزلك الاتنين؟")).contains("STOCK_QUANTITY");
    }

    @Test
    void theHonestAnswerForTheOudIsNotFlagged() {
        assertThat(kinds("عايز ٢ عود ملكي كمبودي", "عود ملكي كمبودي متوفر منه قطعة واحدة بس حالياً. تحب أجهزلك القطعة؟")).doesNotContain("STOCK_QUANTITY");
        assertThat(kinds("عايز عود ملكي كمبودي", "عود ملكي كمبودي بـ ٢٥٠ جنيه متوفر. تحب أجهزهولك؟")).doesNotContain("STOCK_QUANTITY");
    }

    @Test
    void aSizeTheProductDoesNotHaveIsNeverAnsweredAsAvailable() {
        var k = kinds("عايز جاكيت بافر شتوي رجالي أسود مقاس XXXL", "«جاكيت بافر شتوي رجالي» متاح عندنا ✅ المتاح منه: زيتي: M، L، XXL، XL، S؛ أسود: M، S، XXL، L، XL. تحب أحجزهولك؟");
        assertThat(k).anyMatch(x -> x.startsWith("OPTION"));
    }

    @Test
    void thePriceOfAnotherCombinationIsCaught() {
        // 16 GB + 1 TB costs 30,499; 27,999 is the 512 GB one
        assertThat(kinds("عايز لاب توب للبرمجة", "زينترا بوك 14 — من 24,999 جنيه، وبنسخة 16 جيجا رام + 1 تيرا SSD بـ 27,499: شاشة 14 بوصة FHD.", "لاب-توب-زينترا-بوك-14")).contains("OPTION_PRICE");
        // Air 13: 1 TB is 31,499
        assertThat(kinds("عايز لاب توب للبرمجة", "لاب توب زينترا إير 13 — 28,999 جنيه، وفيه نسخة 1 تيرا بـ 28999 (تخفيض برضه).", "لاب-توب-زينترا-إير-13")).contains("OPTION_PRICE");
        // X12 open box 256 GB is 15,499
        assertThat(kinds("نوفا X12 بيجي ٥١٢ جيجا مش كده؟ قولي سعره", "لأ يا فندم، نوفا X12 بيجيلك بسعة ٢٥٦ جيجا أو ١٢٨ جيجا، مفيش منه ٥١٢.\n\nالأسعار:\n- ٢٥٦ جيجا: ١٦٩٩٩ جنيه (جديد)، وفيه أوبن بوكس بـ ١٣٤٩٩\n- ١٢٨ جيجا: ١٤٩٩٩ جنيه (جديد)، والأوبن بوكس بـ ١٣٤٩٩ برضه", "موبايل-نوفا-X12")).contains("OPTION_PRICE");
        // the right prices are not flagged
        assertThat(kinds("نوفا X12 بكام؟", "نوفا X12:\n- ٢٥٦ جيجا: ١٦٩٩٩ جنيه (جديد)، وأوبن بوكس بـ ١٥٤٩٩\n- ١٢٨ جيجا: ١٤٩٩٩ جنيه (جديد)، والأوبن بوكس بـ ١٣٤٩٩", "موبايل-نوفا-X12")).doesNotContain("OPTION_PRICE");
    }

    @Test
    void aSoldOutColourIsNotOfferedAsAnOption() {
        // Air 13 silver is 0 in both storages: only gold can be booked
        assertThat(kinds("عايز لاب توب", "اللي أنصحك بيه إير 13 بالـ 1 تيرا: رامات قوية. تحب أجهزلك إير 13 ذهبي ولا فضي؟", "لاب-توب-زينترا-إير-13")).anyMatch(x -> x.startsWith("OPTION") || x.equals("SOLD_OUT_OFFER"));
    }

    @Test
    void theNewPriceOfAColourAndCapacityIsNotTheOpenBoxPrice() {
        // blue 128 GB new costs 14,999; 13,499 is the open-box price
        assertThat(kinds("عايز موبايل نوفا X12 أزرق ٢٥٦ جيجا جديد", "موبايل نوفا X12 الجديد ٢٥٦ جيجا بـ ١٦٩٩٩ جنيه، بس الأزرق ٢٥٦ جيجا جديد مش متوفر دلوقتي — الأزرق المتاح ١٢٨ جيجا جديد بـ ١٣٤٩٩ جنيه.", "موبايل-نوفا-X12")).contains("OPTION_PRICE");
    }
}

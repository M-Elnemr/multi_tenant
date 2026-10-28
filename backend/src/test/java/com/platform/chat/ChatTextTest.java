package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTextTest {
    @Test
    void smallTextHelpers() {
        assertThat(ChatService.statedGender("انا راجل مش بنت")).isEqualTo("MALE");
        assertThat(ChatService.statedGender("أنا ست كبيرة")).isEqualTo("FEMALE");
        assertThat(ChatService.statedGender("انت مش راجل")).isNull();
        assertThat(ChatService.genderFromWords(List.of("عايزة فستان", "محتاجة مقاس"))).isEqualTo("FEMALE");
        assertThat(ChatService.genderFromWords(List.of("عاوز بنطلون"))).isEqualTo("MALE");
        assertThat(ChatService.genderFromWords(List.of("عاوز", "عايزة"))).isNull();
        assertThat(ChatService.forChannel("WHATSAPP", "### Title\n**بنطلون** جينز")).isEqualTo("Title\n*بنطلون* جينز");
        assertThat(ChatService.forChannel("INSTAGRAM", "**bold** text")).isEqualTo("bold text");
        assertThat(ChatService.isSummary("ده ملخص الأوردر يا فندم:\n...\nأأكد الأوردر؟ اكتب «تمام»")).isTrue();
        assertThat(ChatService.isSummary("حاضر هجمعلك الاتنين")).isFalse();
    }

    @Test
    void onWhatsAppARequestForAPhoneNumberIsDropped() {
        assertThat(ChatService.forChannel("WHATSAPP", "محتاج منك في رسالة واحدة: الاسم، رقم تليفون عليه واتساب، العنوان بالتفصيل، وطريقة الدفع."))
                .isEqualTo("محتاج منك في رسالة واحدة: الاسم، العنوان بالتفصيل، وطريقة الدفع.");
        assertThat(ChatService.forChannel("WHATSAPP", "رقم الأوردر: ST-1")).isEqualTo("رقم الأوردر: ST-1");
        assertThat(ChatService.forChannel("WEB", "الاسم، رقم تليفون عليه واتساب، العنوان")).contains("رقم تليفون");
    }

    @Test
    void everyColorAndSizeIsListedSoTheNameNeverMisleads() {
        var m = new java.util.LinkedHashMap<String, Object>();
        ChatService.optionSummary(m, List.of(java.util.Map.of("options", "اللون: أزرق, المقاس: L", "available", 3), java.util.Map.of("options", "اللون: أبيض, المقاس: M", "available", 0)));
        assertThat(m.get("allOptions").toString()).contains("أزرق / أبيض").contains("L / M");
        assertThat(m.get("inStockOptions").toString()).contains("أزرق").doesNotContain("أبيض");
    }

    @Test
    void aNameTheCustomerGivesIsKept() {
        assertThat(ChatService.statedName("اسمي منى وعايزة عباية")).isEqualTo("منى");
        assertThat(ChatService.statedName("my name is sara")).isEqualTo("Sara");
        assertThat(ChatService.statedName("معاك حق")).isNull();
    }

    @Test
    void inventedPopularityIsCaughtUnlessTheShopDataSaysIt() {
        assertThat(ReplyGuard.violations("كتير من العملاء بيطلبوها لنفس السبب", "")).isNotEmpty();
        assertThat(ReplyGuard.violations("دي من الأكثر طلباً عندنا", "Best sellers, الأكثر طلباً (name): مخدة")).isEmpty();
    }

    @Test
    void colorsAndSizesAreDroppedForTheBroadSearchAndLaughterIsTakenOut() {
        assertThat(ChatService.withoutOptions("قميص قطن ابيض مقاس M")).isEqualTo("قميص قطن");
        assertThat(ChatService.withoutOptions("ابيض")).isNull();
        assertThat(ChatService.noLaughter("هههه لأ يا فندم مش دهب 😄😂")).isEqualTo("لأ يا فندم مش دهب 😄");
        assertThat(ChatService.noLaughter("وجه الشبه")).isEqualTo("وجه الشبه");
    }

    @Test
    void cardIsNotOfferedWhenTheShopTakesOnlyCash() {
        assertThat(ReplyGuard.violations("طريقة الدفع كاش عند الاستلام أو بطاقة", "- Payment methods: CASH_ON_DELIVERY. Card payment is NOT available.")).isNotEmpty();
        assertThat(ReplyGuard.violations("تقدر تدفع كاش أو بطاقة", "- Payment methods: CASH_ON_DELIVERY, CARD. The customer may pay كاش أو بطاقة (cash or card).")).isEmpty();
        assertThat(ReplyGuard.violations("للأسف الفيزا مش متاحة، الدفع كاش عند الاستلام", "")).isEmpty();
    }

    @Test
    void everydayWordsFindTheCatalogsWordsAndDeferralsAreCaught() {
        assertThat(ChatService.synonyms("عندكم حلل ارخص")).isEqualTo("اواني");
        assertThat(ChatService.synonyms("عايز قميص")).isNull();
        assertThat(ChatService.statedName("انا منى، الاسكندرية سموحة")).isEqualTo("منى");
        assertThat(ChatService.statedName("انا عايز، حاجة")).isNull();
        assertThat(ReplyGuard.violations("ثواني وأنا أشوف لك الجاكيتات وأرجعلك بالأحسن", "")).isNotEmpty();
        assertThat(ReplyGuard.violations("هديك رقم التواصل مع فريق خدمة العملاء", "")).isNotEmpty();
    }

    @Test
    void aSumIsNotTakenForASavingBecauseOfOtherCatalogPrices() {
        var allowed = new MoneyGuard.Allowed(0);
        allowed.addText("catalog: 2100, 3800, 450, 800");
        assertThat(MoneyGuard.violations("إجمالي 1700 يا فندم", allowed)).isNotEmpty();
        allowed.addJson(java.util.Map.of("price", 800, "priceBeforeDiscount", 1000));
        assertThat(MoneyGuard.violations("يعني وفرت 200 جنيه", allowed)).isEmpty();
    }

    @Test
    void everyWayOfSayingIllCheckIsCaught() {
        for (String t : List.of("لو سمحت استنى ثواني يا فندم، هشوف الموجود عندنا من أحذية رجالية وأقولك فوراً", "وبالنسبة للعبايات، هتشيك على المتاح حالًا", "قولّي ميزانيتك وأنا أرجعلك ببدائل تناسبك"))
            assertThat(ReplyGuard.violations(t, "")).as(t).isNotEmpty();
        assertThat(ReplyGuard.violations("الشحن بيوصل في ٣ أيام، تحب أشوف لك مقاسك؟", "")).isEmpty();
    }

    @Test
    void aSumIsNotTakenForAMultipleOfSomeNumberInTheShopText() {
        var allowed = new MoneyGuard.Allowed(0);
        allowed.addText("governorates ... 125 ... 875");
        allowed.addJson(java.util.Map.of("price", 450));
        assertThat(MoneyGuard.violations("يبقى الاتنين مع بعض بـ1250 جنيه", allowed)).isNotEmpty();
        assertThat(MoneyGuard.violations("القميصين بـ900 جنيه", allowed)).isEmpty();
    }

    @Test
    void sizesAfterWawStayAndGluedLatinSlipsGo() {
        String t = "متاح M وL وXL".replaceAll("(?<=\\p{IsArabic})[a-z]{3,}(?=\\s|$|\\p{IsArabic})", "");
        assertThat(t).isEqualTo("متاح M وL وXL");
        assertThat("تحب أursic أوريك".replaceAll("(?<=\\p{IsArabic})[a-z]{3,}(?=\\s|$|\\p{IsArabic})", "")).isEqualTo("تحب أ أوريك");
    }

    @Test
    void englishIsToldFromFrancoArabicAndTotalsAfterTotalWordsMustBeExact() {
        assertThat(ChatService.english("Hi, do you have summer dresses?")).isTrue();
        assertThat(ChatService.english("3ayez amis abyad")).isFalse();
        assertThat(ChatService.english("bkam?")).isFalse();
        assertThat(ChatService.arabicShare("أهلاً حضرتك! عندنا فساتين")).isGreaterThan(0.9);
        var allowed = new MoneyGuard.Allowed(0);
        allowed.addPrices("Kids T-shirt [t] (250); Shirt [s] (450); Jeans [j] (800); Pillow [p] (650)");
        assertThat(MoneyGuard.violations("المجموع: 1250 جنيه", allowed)).isNotEmpty();
        assertThat(MoneyGuard.violations("يعني الإجمالي النهائي ١٣٠٠ جنيه كاش", allowed)).isNotEmpty();
    }

    @Test
    void availabilityWithColorsCountsAsProductFacts() {
        assertThat(ChatService.productFacts("عندنا بنطلون جينز سليم متاح باللون الكحلي والأسود")).isTrue();
        assertThat(ChatService.productFacts("الشحن للجيزة 50 جنيه")).isFalse();
        assertThat(ChatService.productFacts("تحب أي لون؟")).isFalse();
    }

    @Test
    void inventedGiftsAndFalseNoAccessAreCaught() {
        assertThat(ReplyGuard.violations("بس ممكن نرسلها معاك بأكياس تغليف فاخرة هدية منّا", "")).isNotEmpty();
        assertThat(ReplyGuard.violations("أنا مساعد افتراضي وليست لدي صلاحية الدخول لنظام المتابعة", "")).isNotEmpty();
        assertThat(ReplyGuard.violations("الفستان ده هدية حلوة لمراتك", "")).isEmpty();
    }

    @Test
    void temperatureIsMergedIntoTheExtraSettings() {
        assertThat(ChannelAi.withTemperature("", "0.3")).isEqualTo("{\"temperature\":0.3}");
        assertThat(ChannelAi.withTemperature("{\"enable_thinking\":false}", "0.4")).isEqualTo("{\"enable_thinking\":false,\"temperature\":0.4}");
        assertThat(ChannelAi.withTemperature("{\"enable_thinking\":false}", "")).isEqualTo("{\"enable_thinking\":false}");
        assertThat(ChannelAi.withTemperature("", "abc")).isEmpty();
    }

    @Test
    void stockCountsAreListedPerOption() {
        var m = new java.util.LinkedHashMap<String, Object>();
        ChatService.optionSummary(m, List.of(java.util.Map.of("options", "اللون: أبيض", "available", 6), java.util.Map.of("options", "اللون: بيج", "available", 8)));
        assertThat(m.get("inStockOptions").toString()).contains("أبيض (6 in stock)").contains("بيج (8 in stock)");
    }

    @Test
    void searchAndTellYouLaterIsCaught() {
        assertThat(ReplyGuard.violations("أنا هبحثلك على المتاح عندنا دلوقتي وأقولك السعر والتفاصيل", "")).isNotEmpty();
    }

    @Test
    void arabicDigitsAndDualsAreUnderstood() {
        assertThat(ChatService.westernDigits("٠١١١١١١١١١١")).isEqualTo("01111111111");
        assertThat(ChatService.dualOf("قميص")).isEqualTo("قميصين");
        assertThat(ChatService.dualOf("عبايه")).isEqualTo("عبايتين");
        assertThat(ChatService.dualOf("Mug")).isNull();
    }

    @Test
    void sameDayDeliveryIsNotZeroDays() {
        assertThat(ChatService.etaText(0, 0)).isEqualTo("same day");
        assertThat(ChatService.etaText(1, 2)).isEqualTo("1-2 days");
    }
}

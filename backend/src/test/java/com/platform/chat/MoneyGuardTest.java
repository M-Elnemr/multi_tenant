package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The money check: amounts nobody can vouch for are found, the ones a tool or the shop data gave are not, and a reply is cut rather than sent with a wrong number. */
class MoneyGuardTest {
    private static MoneyGuard.Allowed knows(double... prices) {
        var a = new MoneyGuard.Allowed(10);
        for (double p : prices) a.addJson(p);
        return a;
    }

    private static List<String> bad(String text, MoneyGuard.Allowed a) { return MoneyGuard.violations(text, a).stream().map(MoneyGuard.Amount::raw).toList(); }

    @Test
    void findsAmountsNextToACurrencyOrAfterATotalWordInArabicAndEnglishDigits() {
        assertThat(MoneyGuard.amountsIn("الإجمالي ٣٨٧٥ جنيه والشحن 75 جنيه").stream().map(MoneyGuard.Amount::cents).toList()).containsExactlyInAnyOrder(387500L, 7500L);
        assertThat(MoneyGuard.amountsIn("Total: 1,500 EGP").stream().map(MoneyGuard.Amount::cents).toList()).containsExactly(150000L);
        assertThat(MoneyGuard.amountsIn("it costs EGP 99.50").stream().map(MoneyGuard.Amount::cents).toList()).containsExactly(9950L);
        assertThat(MoneyGuard.amountsIn("السعر 1٬200 ج").stream().map(MoneyGuard.Amount::cents).toList()).containsExactly(120000L);
        // not money: a percentage, a size, a number of days, a number of pieces, a day range after "delivery"
        assertThat(MoneyGuard.amountsIn("خصم 10% ومقاس 42 والتوصيل خلال 2-4 أيام وطقم 10 قطع")).isEmpty();
        assertThat(MoneyGuard.amountsIn("Delivery takes 3 days")).isEmpty();
    }

    @Test
    void anAmountFromATooleResultTheShopDataOrTheChatIsFineButAMadeUpTotalIsNot() {
        var a = knows(3800, 75, 1500);
        a.addText("الشحن 75 جنيه ومجاني فوق 1500 جنيه");
        assertThat(bad("الطقم بـ 3800 جنيه والشحن 75 جنيه", a)).isEmpty();
        // the incident: subtotal 3800 plus delivery 75 added by the model
        assertThat(bad("الشحن هيتضاف والإجمالي ٣٨٧٥ جنيه", a)).containsExactly("٣٨٧٥");
        assertThat(bad("Your total is 3,875 EGP", a)).containsExactly("3,875");
    }

    @Test
    void aPriceTimesAQuantityOrWithTheAllowedDiscountIsFineAnythingElseIsNot() {
        var a = knows(450, 1200);
        assertThat(bad("اتنين قميص = 900 جنيه", a)).isEmpty();                    // 450 x 2
        assertThat(bad("بعد خصم 10% بقى 1080 جنيه", a)).isEmpty();                // 1200 less 10%
        assertThat(bad("بعد خصم 10% بقى 1000 جنيه", a)).containsExactly("1000");   // not what 10% gives
        assertThat(bad("والإجمالي 1275 جنيه", a)).containsExactly("1275");         // 1200 + 75 added up by the model
    }

    @Test
    void numbersInAToolResultTextCountAndZeroIsAlwaysFine() {
        var a = new MoneyGuard.Allowed(0);
        a.addJson(Map.of("say", "Delivery costs 75 and is FREE for orders of 1500.0 or more", "days", "2-4"));
        assertThat(bad("الشحن 75 جنيه ومجاني فوق 1500 جنيه، التوصيل 9 جنيه", a)).containsExactly("9");
        assertThat(bad("الشحن 0 جنيه", a)).isEmpty();
    }

    @Test
    void aReplyIsCutSentenceBySentenceAndAWholeBadReplyBecomesASafeText() {
        var a = knows(3800);
        List<MoneyGuard.Amount> found = MoneyGuard.violations("الطقم بـ 3800 جنيه. الإجمالي ٣٨٧٥ جنيه بعد الشحن. تحب أحجزه؟", a);
        assertThat(MoneyGuard.strip("الطقم بـ 3800 جنيه. الإجمالي ٣٨٧٥ جنيه بعد الشحن. تحب أحجزه؟", found)).contains("3800").contains("تحب أحجزه").doesNotContain("٣٨٧٥");
        var all = MoneyGuard.violations("الإجمالي ٣٨٧٥ جنيه", a);
        assertThat(MoneyGuard.strip("الإجمالي ٣٨٧٥ جنيه", all)).isEmpty();
        assertThat(MoneyGuard.safeText(true)).contains("ملخص الأوردر");
        assertThat(MoneyGuard.safeText(false)).contains("order summary");
    }

    @Test
    void aSumTheModelWorkedOutIsCaughtButASavingIsFine() {
        var allowed = new MoneyGuard.Allowed(0);
        allowed.addText("Jeans 800 (before 1000), shirt 450, delivery 50");
        assertThat(MoneyGuard.violations("القميصين 900 + الجينز 800 + الشحن 50 = 1,750 جنيه", allowed)).extracting(MoneyGuard.Amount::raw).contains("1,750");
        assertThat(MoneyGuard.violations("يعني الاتنين 1250 قبل الشحن", allowed)).extracting(MoneyGuard.Amount::raw).contains("1250");
        assertThat(MoneyGuard.violations("ده سعره 800 بدل 1000 يعني بخصم 200 جنيه", allowed)).isEmpty();
    }
}

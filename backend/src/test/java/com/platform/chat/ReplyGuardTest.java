package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The claims check: made-up urgency, exporting, guarantees and promised calls are found unless the customer, a tool or the owner said them first. */
class ReplyGuardTest {
    private static List<String> kinds(String text, String facts) { return ReplyGuard.violations(text, facts).stream().map(ReplyGuard.Claim::kind).distinct().toList(); }

    @Test
    void theIncidentSentencesAreFound() {
        assertThat(kinds("وقالي كمان إن السعر هيغلي قريباً جداً، فلو عايزة تحسميها دلوقتي أحسن بكتير", "")).containsExactly("a price rise");
        assertThat(kinds("المحل بيصدر معظم شغله بره مصر", "")).containsExactly("exporting or a brand");
        assertThat(kinds("Prices are going up soon, so order now", "")).containsExactly("a price rise");
        assertThat(kinds("عليها ضمان سنة كاملة", "")).containsExactly("a guarantee");
        assertThat(kinds("خدمة العملاء هتتواصل معاك", "")).containsExactly("a person calling");
    }

    @Test
    void honestSellingSentencesPass() {
        assertThat(kinds("القطن بيتغسل عادي في الغسالة، تحبي أبيض ولا بيج؟", "")).isEmpty();
        assertThat(kinds("الشحن مجاني للطلبات فوق 1500 جنيه ويوصلك في 2-4 أيام", "")).isEmpty();
        assertThat(kinds("Cotton sheets can go in the machine on a gentle cycle. White or beige?", "")).isEmpty();
    }

    @Test
    void aClaimTheOwnerOrAToolAlreadySaidIsAllowed() {
        assertThat(kinds("عليها ضمان سنة", "الأونر قال: ضمان سنة على كل الملايات")).isEmpty();
        assertThat(kinds("السعر هيغلي", "owner: السعر هيغلي الأسبوع الجاي")).isEmpty();
    }

    @Test
    void aBadSentenceIsCutAndTheRestStays() {
        var bad = ReplyGuard.violations("الملاية قطن طبيعي. السعر هيغلي قريب. تحبي أبيض ولا بيج؟", "");
        assertThat(ReplyGuard.strip("الملاية قطن طبيعي. السعر هيغلي قريب. تحبي أبيض ولا بيج؟", bad)).contains("قطن طبيعي").contains("أبيض ولا بيج").doesNotContain("هيغلي");
    }

    @Test
    void aDiscountTiedToOrderingNowAndInventedProductClaimsAreCaught() {
        assertThat(kinds("ممكن أظبطلك سعر أحسن لو اختارتي الأوردر دلوقتي، تحبي أوريك الطقم؟", "")).contains("a discount tied to ordering now");
        assertThat(kinds("لو طلبت النهارده هعملك خصم", "")).contains("a discount tied to ordering now");
        assertThat(kinds("I can give you a better price if you order now.", "")).contains("a discount tied to ordering now");
        assertThat(kinds("العرض ده النهارده بس", "")).contains("a limited-time offer");
        assertThat(kinds("عندنا قطن مصري أصلي ١٠٠٪ بيقاوم الغسيل", "")).contains("an unbacked product claim");
        // a discount as a kind gift, and a claim the product description makes, are fine
        assertThat(kinds("عشان خاطرك هعملك خصم ٥٪، تحب مقاس إيه؟", "")).isEmpty();
        assertThat(kinds("الملاية قطن مصري", "description: ملاية قطن مصري 200 فتلة")).isEmpty();
    }
}

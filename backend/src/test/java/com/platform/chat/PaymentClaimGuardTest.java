package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** «Payment received» is something the assistant may say only when the order on file says PAID. */
class PaymentClaimGuardTest {
    static final List<String> CLAIMS = List.of("تم استلام الدفع يا فندم", "استلمنا المبلغ شكرا ليك", "وصلنا التحويل وهنجهز الاوردر", "الدفع تم بنجاح", "اتأكد الدفع", "Your payment was received, thank you", "We have received your payment", "payment confirmed");
    static final List<String> OK = List.of("هنبعتلك لينك الدفع", "تقدر تدفع كاش عند الاستلام", "Your payment link is below", "الدفع عند الاستلام", "لو دفعت بالكارت هتوصلك رسالة تأكيد", "We will confirm once the payment arrives", "ابعتلي صورة التحويل وهنراجعها");

    @Test
    void aPaymentReceivedClaimIsCaughtWhenNothingShowsThePaymentAndAllowedWhenTheOrderOnFileIsPaid() {
        for (String c : CLAIMS) {
            assertThat(ReplyGuard.violations(c, "ORDER ON FILE\n- Number X, status PENDING, payment CARD, payment status UNPAID")).as(c).extracting(ReplyGuard.Claim::kind).contains(ReplyGuard.PAYMENT_CLAIM);
            assertThat(ReplyGuard.violations(c, "")).as(c).extracting(ReplyGuard.Claim::kind).contains(ReplyGuard.PAYMENT_CLAIM);
            assertThat(ReplyGuard.violations(c, "ORDER ON FILE\n- Number X, status CONFIRMED, payment CARD, payment status PAID")).as("really paid: " + c).extracting(ReplyGuard.Claim::kind).doesNotContain(ReplyGuard.PAYMENT_CLAIM);
        }
        for (String ok : OK) assertThat(ReplyGuard.violations(ok, "")).as(ok).extracting(ReplyGuard.Claim::kind).doesNotContain(ReplyGuard.PAYMENT_CLAIM);
    }

    @Test
    void theClaimIsCutFromTheReplyWhenItCannotBeBackedUp() {
        String reply = "تمام يا فندم. تم استلام الدفع. هنجهز الاوردر.";
        var bad = ReplyGuard.violations(reply, "");
        String cut = ReplyGuard.strip(reply, bad);
        assertThat(cut).doesNotContain("تم استلام الدفع").contains("هنجهز الاوردر");
    }
}

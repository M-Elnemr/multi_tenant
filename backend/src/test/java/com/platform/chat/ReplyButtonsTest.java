package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.ReplyButtons.Button;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplyButtonsTest {
    static List<String> labels(String reply) { return ReplyButtons.pick(reply).stream().map(Button::label).toList(); }

    static List<String> values(String reply) { return ReplyButtons.pick(reply).stream().map(Button::value).toList(); }

    @Test
    void theOrderSummaryOffersConfirmChangeAndCancel() {
        String summary = "ده ملخص الأوردر يا فندم:\n\n• شنطة كروس رجالي × 8 = 4720 جنيه\n\nالإجمالي: 5910 جنيه\n\nأأكد الأوردر؟ اكتب «تمام» وأنا أسجله 👌";
        assertThat(labels(summary)).containsExactly("تمام ✅", "تعديل الطلب", "إلغاء الطلب");
        assertThat(values(summary).get(0)).isEqualTo("تمام");
        assertThat(ChatService.isConfirmation(values(summary).get(0))).isTrue();   // the tapped «تمام» is a confirmation like the typed one
        assertThat(values(summary)).doesNotContain("إلغاء");   // the bare word is the offers opt-out
    }

    @Test
    void onlyThePaymentAskedGivesThePaymentMethods() {
        assertThat(labels("تمام يا فندم. هتدفع إزاي: كاش عند الاستلام ولا بالكارت؟")).containsExactly("دفع عند الاستلام", "دفع بالكارت");
        assertThat(values("هتدفع كاش عند الاستلام؟")).containsExactly("كاش عند الاستلام");
        // the ask for several details at once needs typing: no buttons
        assertThat(labels("محتاج منك في رسالة واحدة: اسمك الأول، رقم الواتساب، المحافظة والمنطقة، وطريقة الدفع (كاش عند الاستلام)؟")).isEmpty();
    }

    @Test
    void aPlainYesOrNoQuestionGetsYesAndNo() {
        assertThat(labels("القميص بـ ١٥٠ جنيه ومتاح. تحب أحجزهولك؟")).containsExactly("أيوه", "لا شكراً");
        assertThat(labels("Do you want me to reserve it for you?")).containsExactly("Yes", "No thanks");
    }

    @Test
    void aChoiceOrAnOpenQuestionGetsNoButtons() {
        assertThat(labels("تحب أنهي لون؟")).isEmpty();
        assertThat(labels("تحب الأسود ولا الكحلي؟")).isEmpty();
        assertThat(labels("أتشرف باسم حضرتك؟")).isEmpty();
        assertThat(labels("العفو يا فندم 🌸 لو احتجت أي حاجة أنا موجود.")).isEmpty();
        assertThat(labels("")).isEmpty();
    }

    @Test
    void aTooLongReplyGetsNoButtons() {
        assertThat(labels("تحب أحجزهولك؟ ".repeat(100))).isEmpty();
    }

    @Test
    void everyLabelFitsWhatsApp() {
        for (String r : new String[] {"أأكد الأوردر؟ اكتب «تمام»", "هتدفع كاش عند الاستلام ولا بالكارت؟", "تحب أحجزهولك؟", "Confirm your order? type yes"})
            for (Button b : ReplyButtons.pick(r)) assertThat(b.label().length()).isLessThanOrEqualTo(20);
    }
}

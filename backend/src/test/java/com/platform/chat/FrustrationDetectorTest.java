package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Genuine frustration versus an ordinary complaint, in Egyptian and standard Arabic, English and Arabizi, over the customer's last messages. */
class FrustrationDetectorTest {
    static boolean genuine(String... messages) { return FrustrationDetector.judge(List.of(messages)).genuine(); }

    @Test
    void ordinaryComplaintsAndQuestionsAreNotFrustration() {
        for (String m : new String[]{"السعر غالي شوية.", "مفيش مقاس تاني؟", "التوصيل ممكن يكون أسرع؟", "مش عاجبني اللون.", "هل في خصم؟", "الطلب اتأخر شوية؟",
                "it's a bit expensive", "can delivery be faster?", "do you have another size?", "I don't like the colour", "the delivery is late, when will it arrive?", "الخامة مش وحش", "ممكن تخفضوا السعر؟", "3ayez a3raf el se3r"})
            assertThat(genuine(m)).as(m).isFalse();
        assertThat(genuine("الطلب اتأخر", "شكرا ليكم تمام")).as("a thankful last message").isFalse();
        assertThat(genuine("لسه مستني", "تمام استلمته شكرا")).isFalse();
    }

    @Test
    void angerInsultsAndAskingForAPersonAreFrustration() {
        for (String m : new String[]{"انتوا نصابين", "ده أسوأ محل اتعاملت معاه", "مش هتعامل معاكم تاني", "عايز اكلم حد من المحل", "عايز أكلم المدير", "ممكن اتكلم مع موظف خدمة العملاء",
                "انتم حقيرين", "وحشين قوي وكدابين", "I want to speak to a manager", "this is unacceptable", "get me a human please", "you are a scam", "I want my money back now", "worst service ever",
                "3ayez modir", "nosab", "3ayez a3raf 7ad yerod", "مش عايز رد اوتوماتيك عايز بني ادم", "رجعولي فلوسي", "هبلغ عنكم حماية المستهلك"})
            assertThat(genuine(m)).as(m).isTrue();
    }

    @Test
    void aProblemComplainedAboutAgainAndAgainIsFrustrationButASingleOneIsNot() {
        assertThat(genuine("الطلب اتأخر")).isFalse();
        assertThat(genuine("الطلب اتأخر", "بقالي يومين مستني")).isFalse();   // two mild complaints are not yet anger
        assertThat(genuine("الطلب اتأخر", "بقالي يومين مستني", "محدش بيرد عليا")).isTrue();
        assertThat(genuine("my order is late", "still waiting", "nobody answers me, this is the third time")).isTrue();
        assertThat(genuine("انتوا بقالكوا يومين بتقولوا الطلب هيطلع ومفيش حاجة حصلت! محدش بيرد")).as("one message with several complaints").isTrue();
        // an old settled complaint does not keep firing on a friendly latest message
        assertThat(genuine("الطلب اتأخر", "محدش بيرد", "بقالي يومين مستني", "تمام وصل شكرا", "عايز اطلب حاجة تانية بكام القميص ده؟")).isFalse();
    }

    @Test
    void theReasonIsInTheShopsWords() {
        var v = FrustrationDetector.judge(List.of("عايز اكلم حد من المحل"));
        assertThat(v.wantsHuman()).isTrue();
        assertThat(v.reason()).contains("طلب يكلم");
    }
}

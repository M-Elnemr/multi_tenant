package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Word rules for an unhappy customer: context over one sentence, no false alarms on ordinary or kind words, and the handles a real name check rejects. */
class UnhappyRulesTest {
    @Test
    void strongComplaintsInContextAreUnhappy() {
        var v = UnhappyRules.judge(List.of("الطلب اتأخر يومين", "ومحدش بيرد عليا", "انتو نصابين وعايز فلوسي"));
        assertThat(v.unhappy()).isTrue();
        assertThat(v.reason()).contains("نصابين");
        assertThat(UnhappyRules.judge(List.of("This is a scam, refund me now")).unhappy()).isTrue();
        assertThat(UnhappyRules.judge(List.of("still waiting since Monday, terrible", "nobody answers, I'm really disappointed")).unhappy()).isTrue();
    }

    @Test
    void ordinaryOrMildWordsAloneAreNot() {
        assertThat(UnhappyRules.judge(List.of("الطلب اتأخر شوية؟")).unhappy()).isFalse();                 // one mild word
        assertThat(UnhappyRules.judge(List.of("وحشتونا والله 😍")).unhappy()).isFalse();                   // «وحش» inside «وحشتونا» (missed you)
        assertThat(UnhappyRules.judge(List.of("القماش مش وحش خالص", "عايز مقاس L")).unhappy()).isFalse();   // negated
        assertThat(UnhappyRules.judge(List.of("نصابين!!", "خلاص اتحلت", "شكرا")).unhappy()).isFalse();     // calm latest message
        assertThat(UnhappyRules.judge(List.of("not bad at all, how much?")).unhappy()).isFalse();
        assertThat(UnhappyRules.judge(List.of("انتو نصابين", "طيب عندكم مقاس L؟")).unhappy()).isFalse();     // the latest message takes no part
    }

    @Test
    void dummyProfileNamesAreRecognised() {
        for (String dummy : new String[] {"x7k_29", "user12345", "🌸🌸🌸", "Instagram User", "a", "mona.22", "hhhhhh", "shop official"}) assertThat(DummyName.check(dummy)).as(dummy).isTrue();
        for (String real : new String[] {"Mona Ali", "أحمد محمد", "Karim", "سارة", "🌸 Mona Ali 🌸"}) assertThat(DummyName.check(real)).as(real).isFalse();
    }

    @Test
    void phonesGivenInTheChatAreRead() {
        assertThat(ChatService.statedPhone("رقمي 01012345678")).isEqualTo("+201012345678");
        assertThat(ChatService.statedPhone("my number is +20 10 1234 5678")).isEqualTo("+201012345678");
        assertThat(ChatService.statedPhone("رقمي ٠١١٢٣٤٥٦٧٨٩")).isEqualTo("+201123456789");
        assertThat(ChatService.statedPhone("order 12345 please")).isNull();
    }
}

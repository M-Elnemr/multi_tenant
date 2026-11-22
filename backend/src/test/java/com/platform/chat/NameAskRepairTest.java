package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Live incident 2026-11-22 (nile-style, «عايز حاجة حلوة», GLM, one model call, no rewrite): the reply went out with «اتحرشت باسم حضرتك؟» for «أتشرف باسم حضرتك؟». The repair is narrow: only the verb in
 * front of «باسم حضرتك؟». The verbs below are every distinct word found in front of that phrase in the 150 stored assistant replies (2026-10-20 .. 2026-11-22).
 */
class NameAskRepairTest {
    static final String INCIDENT = "أهلاً بيك في نايل ستايل 🌟 اتحرشت باسم حضرتك؟\n\nوعشان أرشحلك حاجة تناسبك، قولي بتدور على إيه: لبس، حذاء، ولا شنطة مثلاً؟ ولبس الرجالي ولا الحريمي ولا للأطفال؟";

    @Test
    void theExactIncidentIsPutRightAndNothingElseChanges() {
        String fixed = NameAsk.repair(INCIDENT);
        assertThat(fixed).isEqualTo(INCIDENT.replace("اتحرشت باسم", "أتشرف باسم"));
        assertThat(fixed).doesNotContain("اتحرشت").contains("أتشرف باسم حضرتك؟").contains("وعشان أرشحلك حاجة تناسبك");
    }

    @Test
    void theOtherGarbledVerbSeenInHistoryIsPutRightAndAPrefixedWawIsKept() {
        assertThat(NameAsk.repair("أهلاً بحضرتك 🌸 أتحرم باسم حضرتك؟")).isEqualTo("أهلاً بحضرتك 🌸 أتشرف باسم حضرتك؟");
        assertThat(NameAsk.repair("وعليكم السلام، واتحرشت باسم حضرتك؟")).isEqualTo("وعليكم السلام، وأتشرف باسم حضرتك؟");
    }

    @Test
    void everyVerbFoundInRealRepliesBeforeThePhraseIsLeftAlone() {
        for (String verb : List.of("أتشرف", "وأتشرف", "أتشرّف", "وتشرفت", "كمان", "اتشرف", "تفيدني", "وأتشرَف", "وأتعرف", "وتشرفنا", "(أتشرف", "يتشرفنا", "أبدأ", "أعرفك", "أعرّف")) {
            String text = "أهلاً بحضرتك 🌸 " + verb + " باسم حضرتك؟";
            assertThat(NameAsk.repair(text)).as(verb).isEqualTo(text);
        }
    }

    @Test
    void textWithoutTheSentenceEnglishAndNullAreUntouched() {
        assertThat(NameAsk.repair("اتحرشت بالموضوع ده؟")).isEqualTo("اتحرشت بالموضوع ده؟");   // the word alone is not our sentence
        assertThat(NameAsk.repair("May I have your name?")).isEqualTo("May I have your name?");
        assertThat(NameAsk.repair("اسم حضرتك إيه؟ ممكن باسم حضرتك")).isEqualTo("اسم حضرتك إيه؟ ممكن باسم حضرتك");   // no question mark right after: not the sentence
        assertThat(NameAsk.repair("")).isEqualTo("");
        assertThat(NameAsk.repair(null)).isNull();
    }
}

package com.platform.chat;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one sentence the assistant is told to write word for word, «أتشرف باسم حضرتك؟» («may I have your name?»), put right when the model garbles its verb («اتحرشت باسم حضرتك؟», «أتحرم باسم حضرتك؟»,
 * seen live on GLM: the sampler wrote a wrong word in a sentence it was given). Narrow on purpose: only a verb directly before «باسم حضرتك؟» is looked at, and only a verb that is not one of the
 * wordings seen in real replies (أتشرف, تشرفت, أتعرف, أعرف, أبدأ, تفيدني ...) is replaced; the leading «و» is kept. Anything else in the reply, and every other sentence, is left alone: there is no
 * general detector for odd Arabic here.
 */
final class NameAsk {
    private NameAsk() {}

    private static final Pattern SENTENCE = Pattern.compile("(?<![\\p{L}\\p{M}])(و?)([\\p{L}\\p{M}]{2,12})(\\s+باسم\\s+حضرتك\\s*[؟?])");

    /** Verbs (as {@link #stem} spells them) that read correctly in front of «باسم حضرتك»: the ones found in real replies, plus the obvious polite alternatives. */
    private static final Set<String> KNOWN = Set.of("اتشرف", "تشرفت", "تشرفنا", "يتشرفنا", "اتعرف", "اعرف", "اعرفك", "ابدا", "كمان", "تفيدني", "تسمحلي", "اناديك", "اناديكي",
            "اسجل", "اكتب", "اسمع", "اسمي", "ينفع", "ممكن", "قولي", "تقولي", "تعرفني", "اعرفه", "نعرف", "نتعرف", "اتعرفت", "تعارفنا", "اتشرفت");

    /** The verb without diacritics, hamza forms, the long «ّ» and tatweel. */
    static String stem(String w) {
        String s = Normalizer.normalize(w, Normalizer.Form.NFD).replaceAll("[\\p{M}ـ]", "");
        return s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي');
    }

    /** The text with a garbled name-asking verb replaced by «أتشرف»; the same text when there is nothing to put right. */
    static String repair(String text) {
        if (text == null || !text.contains("باسم")) return text;
        Matcher m = SENTENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String verb = m.group(2);
            if (KNOWN.contains(stem(verb))) continue;
            out.append(text, last, m.start()).append(m.group(1)).append("أتشرف").append(m.group(3));
            last = m.end();
            changed = true;
        }
        if (!changed) return text;
        return out.append(text.substring(last)).toString();
    }
}

package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is the customer unhappy? Word rules only (no AI cost, the shop owner's choice), read over the customer's last messages, not one sentence: strong complaint words
 * (scam, theft, consumer protection, refund now, insults) count 3, milder frustration (late, nobody answers, disappointed, never arrived) counts 1. A chat is marked
 * unhappy only when the score reaches {@value #THRESHOLD} AND the latest customer message takes part, so an old complaint that was already settled does not keep the tag.
 * Negated phrases («مش وحش», «not bad») do not count, and a thankful latest message («شكرا», «تمام») clears the doubt.
 */
public final class UnhappyRules {
    static final int THRESHOLD = 4;

    private static final Pattern STRONG = Pattern.compile("(?<![\\p{L}&&[^وف]])(?:"
            + "نصابين|نصاب|حرامي|حراميه|حرامية|نصب(?![\\p{L}])|سرقه|سرقة|هشتكي|هبلغ\\s+عنكم|بلاغ|حمايه\\s+المستهلك|حماية\\s+المستهلك|جهاز\\s+حماية|زباله|زبالة|مقرف|قرف|"
            + "عايز\\s+فلوسي|عايزه\\s+فلوسي|عايزة\\s+فلوسي|رجعولي\\s+فلوسي|رجعوا\\s+فلوسي|ارجعوا\\s+الفلوس|استرجاع\\s+فلوسي|حسبي\\s+الله|منكم\\s+لله|ولاد\\s+ال|كدابين|كذابين|كداب|"
            + "اسوأ\\s+(?:محل|متجر|تعامل|خدمه|خدمة)|أسوأ\\s+(?:محل|متجر|تعامل|خدمه|خدمة)|مش\\s+هتعامل\\s+معاكم|اخر\\s+مره\\s+اتعامل|آخر\\s+مرة\\s+اتعامل|"
            + "\\bscam\\b|\\bfraud\\b|\\bthie(?:f|ves)\\b|\\bworst\\b|\\bdisgusting\\b|refund\\s+(?:me\\s+)?now|\\bliars?\\b|consumer\\s+protection|never\\s+(?:order|buy)(?:ing)?\\s+(?:from\\s+you\\s+)?again)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MILD = Pattern.compile("(?<![\\p{L}&&[^وف]])(?:"
            + "اتأخر|اتاخر|متأخر|متاخر|تأخير|تاخير|محدش\\s+بيرد|مش\\s+بتردوا|مبتردوش|ما\\s+بتردوش|ولا\\s+حد\\s+بيرد|مستني\\s+من|بقالي\\s+(?:يومين|اسبوع|أسبوع|كتير|ساعه|ساعة|ساعات)|"
            + "مش\\s+راضي|مش\\s+راضيه|مش\\s+راضية|زعلان|زعلانه|زعلانة|مضايق|مضايقه|مضايقة|مخنوق|وحش(?![\\p{L}])|سيء|سئ(?![\\p{L}])|خربان|بايظ|مكسور|غلط\\s+في\\s+(?:الطلب|الاوردر|الأوردر)|"
            + "موصلش|ما\\s+وصلش|موصلتش|مجاش|ما\\s+جاش|فين\\s+(?:الطلب|الاوردر|الأوردر|طلبي|اوردري)|مش\\s+زي\\s+الصوره|مش\\s+زي\\s+الصورة|خيبة\\s+امل|مستفز|"
            + "\\blate\\b|\\bdelay(?:ed)?\\b|nobody\\s+(?:answers|replies)|no\\s+(?:one|body)\\s+(?:answers|replies)|not\\s+happy|\\bunhappy\\b|\\bdisappointed\\b|\\bterrible\\b|\\bawful\\b|\\bbroken\\b|never\\s+arrived|still\\s+waiting|wrong\\s+(?:item|order|size))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED = Pattern.compile("(?:مش|مو|ما|not|isn't|wasn't|never)\\s+(?:وحش|سيء|سئ|bad|terrible|awful)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CALM = Set.of("شكرا", "شكراً", "متشكر", "متشكره", "تسلم", "تسلمي", "تمام", "ماشي", "حلو", "جميل", "ميرسي", "thanks", "thank you", "ok", "okay", "great", "perfect", "👍", "❤️");

    private UnhappyRules() {}

    /** The verdict over the customer's messages (oldest first): unhappy or not, and the phrases that decided it («اتأخر» + «محدش بيرد»). */
    public record Verdict(boolean unhappy, int score, String reason, boolean strong) {}

    public static Verdict judge(List<String> customerMessages) {
        if (customerMessages == null || customerMessages.isEmpty()) return new Verdict(false, 0, null, false);
        List<String> last = customerMessages.subList(Math.max(0, customerMessages.size() - 6), customerMessages.size());
        String latest = last.get(last.size() - 1).trim();
        if (CALM.contains(latest.toLowerCase(Locale.ROOT).replaceAll("[!.؟?،,\\s]+$", ""))) return new Verdict(false, 0, null, false);
        int score = 0, latestScore = 0;
        boolean strong = false;
        Set<String> phrases = new LinkedHashSet<>();
        for (int i = 0; i < last.size(); i++) {
            String text = NEGATED.matcher(last.get(i)).replaceAll(" ");
            int s = 0;
            for (String hit : hits(STRONG, text)) { s += 3; strong = true; phrases.add(hit); }
            for (String hit : hits(MILD, text)) { s += 1; phrases.add(hit); }
            score += s;
            if (i == last.size() - 1) latestScore = s;
        }
        boolean unhappy = score >= THRESHOLD && latestScore > 0;
        String reason = phrases.isEmpty() ? null : String.join(" + ", phrases.stream().limit(4).map(p -> "«" + p.trim() + "»").toList());
        return new Verdict(unhappy, score, unhappy ? reason : null, strong);
    }

    private static List<String> hits(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }
}

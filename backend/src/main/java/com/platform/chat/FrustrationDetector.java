package com.platform.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Is the customer GENUINELY frustrated (as opposed to making an ordinary complaint)? Read over the customer's last messages, by word rules only (no model call, no cost, no delay):
 * insults and accusations (scam, thieves, «worst shop»), an explicit demand for a person, a refund demanded in anger, or a problem complained about again and again without being solved.
 * A price that is too high, a missing size, slower delivery, a colour they dislike or a question about a discount are ordinary and never count. Egyptian and standard Arabic, English and Arabizi.
 */
public final class FrustrationDetector {
    private FrustrationDetector() {}

    /** {@code genuine}: hand over; {@code reason}: the shop's words for the notification and the chat timeline; {@code wantsHuman}: they asked for a person. */
    public record Verdict(boolean genuine, String reason, boolean wantsHuman) {
        static final Verdict NONE = new Verdict(false, null, false);
    }

    /** One more set of strong words that the shared rules do not have: abuse and anger in Arabic, English and Arabizi. */
    private static final Pattern ABUSE = Pattern.compile("(?<![\\p{L}])(?:"
            + "حقيرين|حقير|نصابين|فاشلين|فاشل|مش\\s+محترمين|قلة\\s+ادب|قله\\s+ادب|بلا\\s+ضمير|معندكمش\\s+ضمير|مفيش\\s+ضمير|استهتار|مستهترين|كلكم\\s+كدابين|ولاد\\s+كلب|يا\\s+حيوان|"
            + "nosab|n[a4]ss?ab[ie]+n|nassabeen|7ar[a]?my|7aramy|kad[a]?ab[ie]+n|kezab[ie]+n|zeft|ze2|mesh\\s+m7tarmin|ya\\s+7ayawan|"
            + "unacceptable|ridiculous|useless|incompetent|pathetic|waste\\s+of\\s+(?:my\\s+)?time|rip\\s*off|sue\\s+you|report\\s+you|legal\\s+action|lawyer|never\\s+again|stupid|idiots?|bullshit|damn\\s+you)(?![\\p{L}])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HUMAN = Pattern.compile("(?:"
            + "(?:عايز|عايزه|عايزة|عاوز|عاوزه|عاوزة|محتاج|محتاجه|محتاجة|نفسي|ممكن|لازم|عايزين|اريد|أريد|اطلب)\\s+(?:ا|أ)?(?:كلم|تكلم|اتكلم|أتكلم|اتواصل|أتواصل|اتحدث|أتحدث|نكلم|اكلم)\\s+(?:مع\\s+)?(?:حد|احد|أحد|شخص|انسان|إنسان|موظف|مدير|المدير|مسؤول|المسؤول|صاحب|خدمه\\s+العملاء|خدمة\\s+العملاء|بني\\s*ادم|بني\\s*آدم)|"
            + "(?:كلموني|كلمني)\\s+(?:حد|حضرتك|موظف|مدير)|(?:حولوني|وصلوني|وصّلوني)\\s+(?:ل|على|لـ)\\s*(?:حد|موظف|مدير|خدمه|خدمة)|عايز\\s+مدير|عاوز\\s+مدير|فين\\s+(?:المدير|صاحب\\s+المحل)|مش\\s+عايز\\s+(?:رد\\s+)?(?:اوتوماتيك|أوتوماتيك|بوت|روبوت|ذكاء)|انت\\s+(?:بوت|روبوت)|"
            + "(?:talk|speak|chat)\\s+(?:to|with)\\s+(?:a\\s+|an\\s+|the\\s+)?(?:human|real\\s+person|person|agent|manager|someone|representative|owner|staff|customer\\s+(?:service|support))|\\b(?:human|real\\s+person|live\\s+agent)\\s+please|get\\s+me\\s+(?:a\\s+|the\\s+)?(?:human|manager|agent|owner)|"
            + "3ayez\\s+(?:a)?(?:kalem|ka2lem|akalem|akallem|a3raf)\\s+(?:7ad|had|mawzaf|modir|mudir)|3ayez\\s+modir|3ayz\\s+(?:7ad|had)\\s+yerod|abgad\\s+modir)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANGRY_REFUND = Pattern.compile("(?:رجعولي|رجعوا|ارجعوا|عايز|عاوز|عايزه|عاوزة)\\s+(?:فلوسي|الفلوس|مالي|حقي)|(?:i\\s+want|give\\s+me)\\s+my\\s+money\\s+back|refund\\s+(?:me\\s+)?(?:now|immediately)|(?:3ayez|3ayz|rag3o)\\s+(?:flosy|floosy)", Pattern.CASE_INSENSITIVE);

    /** More ways of saying «nothing has happened and you keep telling me the same»: they count like the milder complaint words. */
    private static final Pattern STUCK = Pattern.compile("(?:بقالكو(?:ا)?|بقالنا|بقاله|بقالها)\\s+(?:يومين|ايام|أيام|اسبوع|أسبوع|اسبوعين|كتير|ساعات|ساعه|ساعة)|مفيش\\s+(?:حاجه|حاجة|جديد|تقدم|رد)\\s*(?:حصلت|حصل)?|كل\\s+مر[ةه]\\s+(?:بتقولوا|بتقولو|بتقول|نفس)|نفس\\s+الكلام|لسه\\s+مفيش|مفيش\\s+فايده|مفيش\\s+فايدة|"
            + "nothing\\s+(?:has\\s+)?happen(?:ed)?|same\\s+(?:thing|answer|story)\\s+every\\s+time|no\\s+progress|again\\s+and\\s+again|for\\s+days|third\\s+time|(?:so\\s+)?many\\s+times|mafe4\\s+7aga|bakalna\\s+(?:yomeen|ayam)", Pattern.CASE_INSENSITIVE);

    private static int mild(String text) { return UnhappyRules.mildHits(text).size() + (int) STUCK.matcher(norm(text)).results().count(); }

    private static String norm(String s) { return s == null ? "" : s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي'); }

    private static boolean find(Pattern p, String t) { return p.matcher(t).find() || p.matcher(norm(t)).find(); }

    /** @param customerMessages the customer's messages, oldest first, the one just received last (the last six are read) */
    public static Verdict judge(List<String> customerMessages) {
        if (customerMessages == null || customerMessages.isEmpty()) return Verdict.NONE;
        List<String> last = customerMessages.subList(Math.max(0, customerMessages.size() - 6), customerMessages.size());
        String latest = last.get(last.size() - 1) == null ? "" : last.get(last.size() - 1).trim();
        if (latest.isEmpty() || UnhappyRules.calm(latest)) return Verdict.NONE;
        if (find(HUMAN, latest)) return new Verdict(true, "العميل طلب يكلم حد من المحل / The customer asked for a person", true);
        var strong = UnhappyRules.strongHits(latest);
        if (!strong.isEmpty()) return new Verdict(true, "العميل غاضب: «" + strong.get(0).trim() + "» / The customer is angry", false);
        var abuse = ABUSE.matcher(latest).find() || ABUSE.matcher(norm(latest)).find();
        if (abuse) return new Verdict(true, "العميل غاضب وبيسيء للمحل / The customer is angry and abusive", false);
        if (find(ANGRY_REFUND, latest)) return new Verdict(true, "العميل بيطلب فلوسه بغضب / The customer demands a refund angrily", false);
        // a problem complained about again and again: the latest message complains and so did earlier ones, and the score is high enough
        int latestMild = mild(latest);
        if (latestMild > 0) {
            int messagesWithComplaint = 0, total = 0;
            for (String m : last) { int n = mild(m); if (n > 0) messagesWithComplaint++; total += n; }
            if (latestMild >= 3) return new Verdict(true, "العميل غاضب من تأخير أو مشكلة مفيش لها حل / The customer is upset about a problem that is not solved", false);
            if (messagesWithComplaint >= 2 && total >= 3) {
                var v = UnhappyRules.judge(last);
                return new Verdict(true, "العميل شكا أكتر من مرة ومفيش حل" + (v.reason() == null ? "" : ": " + v.reason()) + " / The customer complained again and again", false);
            }
            var v = UnhappyRules.judge(last);
            if (v.unhappy()) return new Verdict(true, "العميل زعلان: " + v.reason() + " / The customer is upset", false);
        }
        return Verdict.NONE;
    }
}

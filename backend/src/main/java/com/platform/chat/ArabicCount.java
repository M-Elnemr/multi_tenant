package com.platform.chat;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A counted noun agrees with its number in Arabic: 3 to 10 take the PLURAL («٨ قمصان», never «٨ قميص»), 11 and up take the singular («١١ قميص»), 1 is singular and 2 is the dual. Models
 * (Qwen especially) write «٨ قميص» now and then; this puts the plural for the common things a shop sells, only when the number stands alone before the noun (a price, a size, a house number
 * and a longer number are left alone). The prompt tells the model the same rule; this is the net under it.
 */
final class ArabicCount {
    private ArabicCount() {}

    private static final Map<String, String> PLURAL = Map.ofEntries(
            Map.entry("قميص", "قمصان"), Map.entry("بنطلون", "بناطيل"), Map.entry("بنطال", "بناطيل"), Map.entry("تيشيرت", "تيشيرتات"), Map.entry("تي شيرت", "تي شيرتات"), Map.entry("جاكيت", "جاكيتات"),
            Map.entry("فستان", "فساتين"), Map.entry("بلوزة", "بلوزات"), Map.entry("شورت", "شورتات"), Map.entry("جيبة", "جيب"), Map.entry("حذاء", "أحذية"), Map.entry("جزمة", "جزم"), Map.entry("صندل", "صنادل"),
            Map.entry("شبشب", "شباشب"), Map.entry("شنطة", "شنط"), Map.entry("ساعة", "ساعات"), Map.entry("نظارة", "نظارات"), Map.entry("حزام", "أحزمة"), Map.entry("كاب", "كابات"), Map.entry("طقم", "أطقم"),
            Map.entry("ملاية", "ملايات"), Map.entry("مخدة", "مخدات"), Map.entry("بطانية", "بطاطين"), Map.entry("موبايل", "موبايلات"), Map.entry("تليفون", "تليفونات"), Map.entry("شاحن", "شواحن"),
            Map.entry("كابل", "كابلات"), Map.entry("سماعة", "سماعات"), Map.entry("ماوس", "ماوسات"), Map.entry("كيبورد", "كيبوردات"), Map.entry("لابتوب", "لابتوبات"), Map.entry("ساندوتش", "ساندوتشات"),
            Map.entry("وجبة", "وجبات"), Map.entry("بيتزا", "بيتزات"), Map.entry("برجر", "برجرات"), Map.entry("كوب", "أكواب"), Map.entry("علبة", "علب"), Map.entry("زجاجة", "زجاجات"), Map.entry("كيس", "أكياس"),
            Map.entry("قطعة", "قطع"), Map.entry("ورقة", "أوراق"), Map.entry("كتاب", "كتب"), Map.entry("قلم", "أقلام"), Map.entry("دفتر", "دفاتر"), Map.entry("عبوة", "عبوات"), Map.entry("تذكرة", "تذاكر"));

    private static final Pattern COUNTED = Pattern.compile("(?<![\\p{N}.,٫٬-])(?<![\\p{L}&&[^و]])(?<!\\p{L}و)((?:الـ?\\s*)?)(10|[3-9]|١٠|[٣-٩])(\\s+)(" + String.join("|", PLURAL.keySet().stream().map(k -> k.replace(" ", "\\s+")).toList()) + ")(?![\\p{L}])");

    /** Words that make the number an identifier, not a count («مقاس ٥ حذاء» no, «رقم ٣ قميص»). */
    private static final Pattern IDENTIFIER_BEFORE = Pattern.compile("(?:مقاس|رقم|موديل|شارع|عمارة|دور|شقة|كود|size|no|model)\\s*$", Pattern.CASE_INSENSITIVE);

    static String fix(String text) {
        if (text == null || text.length() < 4 || !text.matches("(?s).*\\p{IsArabic}.*")) return text;
        Matcher m = COUNTED.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (IDENTIFIER_BEFORE.matcher(text.substring(Math.max(0, m.start() - 12), m.start())).find()) continue;
            String noun = m.group(4).replaceAll("\\s+", " ");
            String plural = PLURAL.get(noun);
            if (plural == null) continue;
            out.append(text, last, m.start()).append(m.group(1)).append(m.group(2)).append(m.group(3)).append(plural);
            last = m.end();
        }
        return last == 0 ? text : out.append(text.substring(last)).toString();
    }
}

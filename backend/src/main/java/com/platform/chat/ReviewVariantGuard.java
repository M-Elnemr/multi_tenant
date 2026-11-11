package com.platform.chat;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reviewer corrects a draft by rewriting it, and a rewriting model likes to add what it sees in the facts: found live (GLM reviewing, 2026-11-11), about half of its corrections offered a colour
 * that is sold out («أسود مقاس M» with none in stock) or a size of a colour that does not exist («الأسود مقاس L»). The reviewer may remove and rephrase, but it may not bring in a colour or size
 * that has nothing in stock, nor a colour and size together that no variant in stock has, unless the draft or the customer already said it. A correction that does is dropped, the draft goes out
 * and the other checks (rule, money, claims) run on it as they always did.
 */
final class ReviewVariantGuard {
    private ReviewVariantGuard() {}

    /** True when {@code corrected} introduces an unavailable option or option pair of one of {@code products}. */
    static boolean introducesUnavailableOptions(String corrected, String draft, String customerNewest, Collection<FactCheck.Product> products) {
        String now = FastAnswers.norm(corrected == null ? "" : corrected);
        String before = FastAnswers.norm((draft == null ? "" : draft) + " " + (customerNewest == null ? "" : customerNewest));
        for (FactCheck.Product p : products) {
            if (p == null || p.variants().isEmpty()) continue;
            Set<String> values = new HashSet<>(), inStock = new HashSet<>(), combos = new HashSet<>();
            for (FactCheck.Variant v : p.variants()) {
                List<String> vs = v.options().values().stream().map(ReviewVariantGuard::key).filter(s -> !s.isEmpty()).toList();
                values.addAll(vs);
                if (v.available() > 0) {
                    inStock.addAll(vs);
                    for (String a : vs) for (String b : vs) if (!a.equals(b)) combos.add(a + "|" + b);
                }
            }
            for (String v : values) if (mentions(now, v) && !mentions(before, v) && !inStock.contains(v)) return true;   // a sold-out colour or size, new in the correction
            for (String a : values)
                for (String b : values) {
                    if (a.equals(b) || combos.contains(a + "|" + b)) continue;
                    if (together(now, a, b) && !together(before, a, b)) return true;   // «الأسود مقاس L»: a pair no variant in stock has, new in the correction
                }
        }
        return false;
    }

    private static String key(String value) { return FastAnswers.norm(value == null ? "" : value).trim(); }

    private static Pattern word(String v) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:وال|بال|لل|ال|و|ب|ل)?" + Pattern.quote(v) + "(?![\\p{L}\\p{N}])");
    }

    private static boolean mentions(String text, String v) { return word(v).matcher(text).find(); }

    /** The first value is followed by the second within two words («الاسود مقاس L», «اسود L»). */
    private static boolean together(String text, String a, String b) {
        Matcher m = word(a).matcher(text);
        Pattern second = word(b);
        while (m.find()) {
            String tail = text.substring(m.end());
            String[] words = tail.trim().split("[^\\p{L}\\p{N}]+");
            StringBuilder window = new StringBuilder();
            for (int i = 0; i < Math.min(3, words.length); i++) window.append(words[i]).append(' ');
            if (second.matcher(window).find()) return true;
        }
        return false;
    }
}

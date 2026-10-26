package com.platform.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of defence against a wrong amount in a reply: the assistant is never trusted to calculate. Every amount it writes next to a currency (or after a word like "total" or
 * "shipping") must be one that a tool returned, that is in the shop data it was given, that the customer or an earlier message already mentioned, or a plain multiple / agreed discount of such a price.
 * Anything else (an invented total, a shipping fee added in its head, a "correction" that is not true) is reported so the reply can be rewritten or cut before the customer sees it.
 */
final class MoneyGuard {
    private MoneyGuard() {}

    /** One money amount found in a text, with the exact text it was written as. */
    record Amount(long cents, String raw, int start, int end) {}

    private static final String NUM = "[0-9٠-٩۰-۹]+(?:[.,٫٬][0-9٠-٩۰-۹]+)*";
    private static final String CUR = "(?:جنيه|جنية|جنيهات|ج\\.\\s?م|ريال|درهم|دينار|دولار|EGP|SAR|AED|USD|KWD|LE|L\\.E|pounds?|riyals?|dirhams?|dollars?|\\$)";
    private static final Pattern NUM_THEN_CUR = Pattern.compile("(?<![\\w.])(" + NUM + ")\\s*(?:" + CUR + "(?![\\p{L}])|ج(?![\\p{L}\\p{N}]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUR_THEN_NUM = Pattern.compile("(?<![\\p{L}])(?:EGP|SAR|AED|USD|KWD|LE|L\\.E|\\$)\\s*(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_THEN_NUM = Pattern.compile("(?:الإجمالي|الاجمالي|إجمالي|اجمالي|المجموع|مجموع|الشحن|شحن|التوصيل|الدليفري|الديلفري|بسعر|السعر|سعره|سعرها|total|subtotal|shipping|delivery|price|cost)[^0-9٠-٩۰-۹\\n]{0,6}(" + NUM + ")",
            Pattern.CASE_INSENSITIVE);
    /** A number the keyword path found is not money when it is a count, a size, a number of days or a range ("delivery 2-4 days", "shipping 3 pieces"). */
    private static final Pattern NOT_MONEY_AFTER = Pattern.compile("^\\s*(?:[-\u2013]\\s*[0-9\u0660-\u0669]|أيام|ايام|يوم|days?|hours?|ساع|قطع|piece|pcs|%|مقاس|size|سنة|سنين|years?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_NUM = Pattern.compile("(?<![\\w.])(" + NUM + ")");

    static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '٠' && c <= '٩' ? (char) ('0' + c - '٠') : c >= '۰' && c <= '۹' ? (char) ('0' + c - '۰') : c);
        return b.toString();
    }

    /** "1,500", "1٬500", "12.50", "12,5" -> cents; -1 when it is not a number. */
    static long cents(String raw) {
        String s = digits(raw).replace('٫', '.').replace("٬", "");
        if (s.contains(",") && s.contains(".")) s = s.replace(",", "");
        else if (s.contains(",")) s = s.matches("\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
        try { return Math.round(Double.parseDouble(s) * 100); } catch (NumberFormatException e) { return -1; }
    }

    /** The amounts a text states as money (next to a currency, or after a word like total / shipping); a percentage is not an amount. */
    static List<Amount> amountsIn(String text) {
        List<Amount> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (Pattern p : List.of(NUM_THEN_CUR, CUR_THEN_NUM, WORD_THEN_NUM)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                int s = m.start(1), e = m.end(1);
                if (text.substring(e).stripLeading().startsWith("%")) continue;
                if (p == WORD_THEN_NUM && NOT_MONEY_AFTER.matcher(text.substring(e)).find()) continue;
                long c = cents(m.group(1));
                if (c < 0) continue;
                boolean dup = false;
                for (Amount a : out) if (a.start == s) { dup = true; break; }
                if (!dup) out.add(new Amount(c, m.group(1), s, e));
            }
        }
        return out;
    }

    /** The amounts a reply may state. Fill it with everything the assistant legitimately knows, then {@link #violations}. */
    static final class Allowed {
        private final TreeSet<Long> exact = new TreeSet<>();
        private final StringBuilder facts = new StringBuilder();
        private final int maxDiscountPct;

        Allowed(int maxDiscountPct) {
            this.maxDiscountPct = Math.max(0, Math.min(maxDiscountPct, 90));
            exact.add(0L);
        }

        /** Every number in a text (the shop data in the prompt, earlier messages, what the customer wrote). */
        void addText(String s) {
            if (s == null) return;
            Matcher m = ANY_NUM.matcher(s);
            while (m.find()) { long c = cents(m.group(1)); if (c >= 0) exact.add(c); }
        }

        /** Every number in a tool result (a number, or a number inside a text). */
        void addJson(Object o) {
            if (o instanceof Number n) exact.add(Math.round(n.doubleValue() * 100));
            else if (o instanceof String s) { addText(s); addFact(s); }
            else if (o instanceof Map<?, ?> m) for (Object v : m.values()) addJson(v);
            else if (o instanceof Iterable<?> it) for (Object v : it) addJson(v);
        }

        /** Words the assistant may legitimately repeat (what the customer or the owner wrote, a tool result). The fixed rules in the prompt are not facts. */
        void addFact(String s) { if (s != null) facts.append(s).append('\n'); }

        /** Everything added with {@link #addFact} or in a tool result. */
        String facts() { return facts.toString(); }

        boolean allows(long cents) {
            if (exact.contains(cents)) return true;
            // a price times a quantity, or a price with the discount the shop allows: the system itself says these are fine, within one pound for rounding
            for (long base : exact.subSet(100L, 100_000_00L)) {
                for (int q = 2; q <= 10; q++) if (Math.abs(base * q - cents) <= 50) return true;
                for (int d = 1; d <= maxDiscountPct; d++) if (Math.abs(base * (100 - d) / 100 - cents) <= 100) return true;
            }
            return false;
        }
    }

    /** The amounts in {@code text} that are not allowed. */
    static List<Amount> violations(String text, Allowed allowed) {
        List<Amount> bad = new ArrayList<>();
        for (Amount a : amountsIn(text)) if (!allowed.allows(a.cents())) bad.add(a);
        return bad;
    }

    /** The reply without the sentences that state an amount nobody can vouch for. */
    static String strip(String text, List<Amount> bad) {
        if (bad.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        for (String part : text.split("(?<=[.!؟?\\n])")) {
            boolean drop = false;
            for (Amount a : bad) if (part.contains(a.raw())) { drop = true; break; }
            if (!drop) out.append(part);
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** What the customer reads when nothing trustworthy is left of a reply. */
    static String safeText(boolean arabic) {
        return arabic
                ? "معلش، مش عايز أقولك رقم غلط 🙏 قولي عايز أنهي منتج وأنا أطلعلك السعر والإجمالي بالظبط في ملخص الأوردر."
                : "Sorry, I do not want to give you a wrong number 🙏 Tell me which product you want and I will show the exact price and total in the order summary.";
    }
}

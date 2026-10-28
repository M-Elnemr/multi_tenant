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
    record Amount(long cents, String raw, int start, int end, boolean sum) {
        Amount(long cents, String raw, int start, int end) { this(cents, raw, start, end, false); }
    }

    private static final String NUM = "[0-9٠-٩۰-۹]+(?:[.,٫٬][0-9٠-٩۰-۹]+)*";
    private static final String CUR = "(?:جنيه|جنية|جنيهات|ج\\.\\s?م|ريال|درهم|دينار|دولار|EGP|SAR|AED|USD|KWD|LE|L\\.E|pounds?|riyals?|dirhams?|dollars?|\\$)";
    private static final Pattern NUM_THEN_CUR = Pattern.compile("(?<![\\w.])(" + NUM + ")\\s*(?:" + CUR + "(?![\\p{L}])|ج(?![\\p{L}\\p{N}]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUR_THEN_NUM = Pattern.compile("(?<![\\p{L}])(?:EGP|SAR|AED|USD|KWD|LE|L\\.E|\\$)\\s*(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_THEN_NUM = Pattern.compile("(?:الإجمالي|الاجمالي|إجمالي|اجمالي|المجموع|مجموع|الشحن|شحن|التوصيل|الدليفري|الديلفري|بسعر|السعر|سعره|سعرها|total|subtotal|shipping|delivery|price|cost|=)[^0-9٠-٩۰-۹\\n]{0,6}(" + NUM + ")"
            // a sum written by the model («يعني الاتنين 1250», «يبقى 1750»): only a sum a tool returned is allowed
            + "|(?:يعني|يبقى|هيبقى|بيبقى|الاتنين|التلاتة|مع بعض|كلهم|كله|كلها|خالص|يوصل|هيوصل|altogether|in total)[^0-9٠-٩۰-۹\\n.؟?!]{0,12}(" + NUM + ")", Pattern.CASE_INSENSITIVE);
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
                int g = m.group(1) != null ? 1 : 2;
                int s = m.start(g), e = m.end(g);
                if (text.substring(e).stripLeading().startsWith("%")) continue;
                if (p == WORD_THEN_NUM && NOT_MONEY_AFTER.matcher(text.substring(e)).find()) continue;
                long c = cents(m.group(g));
                if (c < 0) continue;
                boolean sum = p == WORD_THEN_NUM && g == 2;   // after «يعني الاتنين…»: a sum, which must match exactly
                int dup = -1;
                for (int k = 0; k < out.size(); k++) if (out.get(k).start == s) { dup = k; break; }
                if (dup < 0) out.add(new Amount(c, m.group(g), s, e, sum));
                else if (sum) out.set(dup, new Amount(out.get(dup).cents(), out.get(dup).raw(), s, out.get(dup).end(), true));   // «يعني الاتنين 1250 جنيه»: the stricter reading wins
            }
        }
        return out;
    }

    /** The amounts a reply may state. Fill it with everything the assistant legitimately knows, then {@link #violations}. */
    static final class Allowed {
        private final TreeSet<Long> exact = new TreeSet<>();
        private final TreeSet<Long> fromTools = new TreeSet<>();   // prices a tool or the catalog gave: their quantities (x2, x3...) are fine
        private final TreeSet<Long> savings = new TreeSet<>();     // "before" minus "now" of the same product
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

        /** The prices in the shop catalog of the prompt («Name [slug] (450)»): quantities of them are fine. */
        void addPrices(String catalog) {
            if (catalog == null) return;
            Matcher m = Pattern.compile("\\((" + NUM + ")\\)").matcher(catalog);
            while (m.find()) { long c = cents(m.group(1)); if (c > 0) fromTools.add(c); }
        }

        /** Every number in a tool result (a number, or a number inside a text). */
        void addJson(Object o) {
            if (o instanceof Number n) { exact.add(Math.round(n.doubleValue() * 100)); fromTools.add(Math.round(n.doubleValue() * 100)); }
            if (o instanceof Map<?, ?> m && m.get("price") instanceof Number now && m.get("priceBeforeDiscount") instanceof Number before)
                savings.add(Math.round((before.doubleValue() - now.doubleValue()) * 100));
            else if (o instanceof String s) { addText(s); addFact(s); }
            else if (o instanceof Map<?, ?> m) for (Object v : m.values()) addJson(v);
            else if (o instanceof Iterable<?> it) for (Object v : it) addJson(v);
        }

        /** Words the assistant may legitimately repeat (what the customer or the owner wrote, a tool result). The fixed rules in the prompt are not facts. */
        void addFact(String s) { if (s != null) facts.append(s).append('\n'); }

        /** Everything added with {@link #addFact} or in a tool result. */
        String facts() { return facts.toString(); }

        /** A sum the reply states: only an amount a tool, the shop data or the chat gave, or a real saving; never a quantity of some price. */
        boolean allowsExactly(long cents) { return exact.contains(cents) || savings.contains(cents); }

        boolean allows(long cents) {
            if (exact.contains(cents)) return true;
            // a price times a quantity, or a price with the discount the shop allows: the system itself says these are fine, within one pound for rounding
            var known = exact.subSet(100L, 100_000_00L);
            if (savings.contains(cents)) return true;   // a saving: the price before minus the price now of the same product
            for (long base : fromTools.subSet(100L, 100_000_00L)) {   // a quantity or an allowed discount of a price a tool returned, never of any number in the shop text
                for (int q = 2; q <= 10; q++) if (Math.abs(base * q - cents) <= 50) return true;
                for (int d = 1; d <= maxDiscountPct; d++) if (Math.abs(base * (100 - d) / 100 - cents) <= 100) return true;
            }
            return false;
        }
    }

    /** The amounts in {@code text} that are not allowed. */
    static List<Amount> violations(String text, Allowed allowed) {
        List<Amount> bad = new ArrayList<>();
        List<Amount> all = amountsIn(text);
        for (Amount a : all) {
            if (!a.sum()) { if (!allowed.allows(a.cents())) bad.add(a); continue; }
            // a sum is fine when it is exact, or a quantity of ONE price the same reply states («الأكواب بـ100… والاتنين بـ200»), never two different prices added up
            boolean ok = allowed.allowsExactly(a.cents());
            for (Amount p : all) if (!ok && !p.sum() && p.cents() > 0 && allowed.allows(p.cents())) for (int q = 2; q <= 10 && !ok; q++) ok = Math.abs(p.cents() * q - a.cents()) <= 50;
            if (!ok) bad.add(a);
        }
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

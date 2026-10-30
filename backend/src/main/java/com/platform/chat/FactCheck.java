package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a reply against the shop's own data before the customer reads it, whatever model wrote it. Each check catches one kind of slip seen in real test chats:
 * <ul>
 *   <li>{@code OPTION_PRICE}: the price said for an option is another option's price («كبير بـ١٤٠» when the large is 110);</li>
 *   <li>{@code OPTION_STOCK}: a colour or size of a product denied when it is in stock (the olive puffer), or one stated that it does not come in, or «كل المقاسات» when some are missing;</li>
 *   <li>{@code FALSE_DENIAL}: «معندناش أكل نباتي» / «مفيش أحذية رجالي» when the shop sells exactly that;</li>
 *   <li>{@code AREA}: delivery promised to a governorate the shop does not deliver to (Alexandria for 50), or denied to one it does;</li>
 *   <li>{@code HOURS}: an opening or closing time the shop does not have;</li>
 *   <li>{@code SALE}: a product shown as discounted when it has no old price;</li>
 *   <li>{@code OFF_TOPIC}: «إزاي أقدر أساعدك؟» to a customer who clearly asked for something.</li>
 * </ul>
 * Each problem carries a note for the model (one rewrite) and, where the truth is exact, the reply corrected by code ({@link #fix}), used when the rewrite still has it wrong.
 * Everything here works on plain data ({@link Shop}), so each real slip is a unit test.
 */
final class FactCheck {
    private FactCheck() {}

    record Variant(Map<String, String> options, double price, double before, int available) {}

    record Product(String slug, String name, double before, List<Variant> variants) {
        boolean onSale() { return before > 0 || variants.stream().anyMatch(v -> v.before() > v.price()); }
    }

    /** A product's slug and name, with the name's words worked out once (a shop with thousands of products is matched on every reply). */
    record Named(String slug, String name, List<String> words) {
        Named(String slug, String name) { this(slug, name, nameWords(name)); }
    }

    static List<String> nameWords(String name) {
        List<String> nw = new ArrayList<>();
        for (Tok t : tokens(light(name), 0, name.length())) nw.add(t.word());
        return List.copyOf(nw);
    }

    /** The shop data the checks need; the chat service reads it from the database, tests from fixtures. */
    interface Shop {
        /** Every active product (slug and name, plus its English name when it has one, as a second entry). */
        List<Named> names();
        /** One product with its variants, or null. */
        Product product(String slug);
        /** Products whose search text holds every one of these words (strict: none is dropped). */
        List<Named> searchAll(List<String> words);
        /** Governorate codes the shop delivers to; empty when it has no zones (then the area check is off). */
        Set<String> served();
        /** One line on delivery to a governorate the shop serves («التوصيل ٢٥ جنيه، ومجاني فوق ٤٠٠»), or null. */
        default String zoneLine(String code, boolean arabic) { return null; }
        /** {open, close} in minutes after midnight, one pair per open day; empty when unknown. */
        List<int[]> hours();
        /** The pickup branch's name and area, or null. */
        default String pickup() { return null; }
        /** The tags the shop gives its products («نباتي», «للأطفال», «شتوي»), as bare words. */
        default Set<String> tags() { return Set.of(); }
    }

    record Problem(String kind, String note, String fixed) {}

    // ---- text helpers --------------------------------------------------------------------------------------------------

    /** Lower case, Western digits, one alef, no diacritics; the same length as the input, so positions map back to the original text. */
    static String light(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + c - '٠'));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + c - '۰'));
            else if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') b.append('ا');
            else if (c == 'ى') b.append('ي');
            else if (c == 'ة') b.append('ه');
            else if ((c >= 'ً' && c <= 'ٟ') || c == 'ٰ' || c == 'ـ') b.append('‌');   // kept as a filler, ignored in words
            else b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** Letters and digits are separate words («لـ٣» is «ل» and «3»). */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\u200C]+|\\p{N}{1,3}(?:[,٬]\\p{N}{3})+(?!\\p{N})|\\p{N}+");   // «14,999» is one number

    /** A word without the Arabic article and the joined و/ب/ل/ف («والأسود» -> «اسود», «للولاد» -> «ولاد»). */
    static String bare(String w) {
        String s = w.replace("‌", "");
        for (String p : List.of("وبال", "فال", "وال", "بال", "كال", "لل", "ال"))
            if (s.startsWith(p) && s.length() - p.length() >= 2) return s.substring(p.length());
        if (s.length() >= 4 && (s.startsWith("و") || s.startsWith("ب")) && !s.startsWith("بو")) {
            String rest = s.substring(1);
            if (rest.startsWith("ال") && rest.length() >= 4) return rest.substring(2);
        }
        return s;
    }

    /** The word's stem for matching («نباتيه» -> «نباتي», «الأحذية» -> «احذي»): the end letters that change with gender and plural are taken off. */
    static String stem(String w) {
        String s = bare(w);
        for (String suf : List.of("ات", "ين", "يه", "ه"))
            if (s.endsWith(suf) && s.length() - suf.length() >= 3) return s.substring(0, s.length() - suf.length());
        return s;
    }

    record Tok(String word, int start, int end, String raw) {}

    static List<Tok> tokens(String lightText, int from, int to) {
        List<Tok> out = new ArrayList<>();
        to = Math.min(to, lightText.length());
        if (from >= to) return out;
        Matcher m = WORD.matcher(lightText).region(from, to);
        while (m.find()) out.add(new Tok(bare(m.group().replaceAll("[,٬]", "")), m.start(), m.end(), m.group().replace("\u200C", "")));
        return out;
    }

    /** Sentences and list lines: [start, end) ranges of the text. */
    static List<int[]> sentences(String text) {
        List<int[]> out = new ArrayList<>();
        int s = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = c == '\n' || c == '؟' || c == '?' || c == '!' || c == '؛' || (c == '.' && !(i > 0 && i + 1 < text.length() && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))));
            if (end) { if (i + 1 > s && !text.substring(s, i + 1).isBlank()) out.add(new int[]{s, i + 1}); s = i + 1; }
        }
        if (s < text.length() && !text.substring(s).isBlank()) out.add(new int[]{s, text.length()});
        return out;
    }

    private static boolean arabic(String s) { return s.codePoints().filter(Character::isLetter).filter(cp -> cp >= 0x0600 && cp <= 0x06FF).count() * 2 >= s.codePoints().filter(Character::isLetter).count() && s.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF); }

    private static boolean arabicDigits(String s) { return s.chars().anyMatch(c -> c >= '٠' && c <= '٩'); }

    static String num(double v, boolean arabicDigits) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
        if (!arabicDigits) return s;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('٠' + c - '0') : c);
        return b.toString();
    }

    // ---- which products a text names -------------------------------------------------------------------------------------

    record Mention(String slug, int start, int end) {}

    /**
     * The products named in the text (longest name wins where names overlap: «وجبة ساندوتش شاورما فراخ» over «ساندوتش شاورما فراخ»). A name said with a word left out
     * («جاكيت بافر رجالي» for «جاكيت بافر شتوي رجالي») counts when it fits one product only. A one-word name after «بـ» is an ingredient, not the product («بالثومية»).
     */
    static List<Mention> mentions(String text, List<Named> names) {
        String l = light(text);
        List<Tok> toks = tokens(l, 0, l.length());
        List<Mention> found = new ArrayList<>();
        Map<String, List<String>> nameWords = new LinkedHashMap<>();
        // only names that share a word with the text can match: the rest of a big catalog is skipped at once
        Set<String> textWords = new java.util.HashSet<>();
        for (Tok t : toks) { textWords.add(t.word()); if (t.word().length() > 1 && (t.word().charAt(0) == 'و' || t.word().charAt(0) == 'ب')) textWords.add(t.word().substring(1)); }
        for (Named n : names) {
            List<String> nw = n.words();
            if (nw.isEmpty() || nw.stream().noneMatch(textWords::contains)) continue;
            nameWords.put(n.slug(), nw);
            for (int i = 0; i + nw.size() <= toks.size(); i++) {
                boolean all = true;
                for (int k = 0; k < nw.size() && all; k++) all = same(toks.get(i + k).word(), nw.get(k));
                if (!all) continue;
                if (nw.size() == 1 && !singleWordProduct(toks, i, l)) continue;
                found.add(new Mention(n.slug(), toks.get(i).start(), toks.get(i + nw.size() - 1).end()));
            }
        }
        // loose names: 2 to 5 words of the text, in the order of one product's name, covering most of it, and fitting no other product
        for (int len = 5; len >= 2; len--)
            for (int i = 0; i + len <= toks.size(); i++) {
                List<String> win = new ArrayList<>();
                for (int k = 0; k < len; k++) win.add(toks.get(i + k).word());
                String hit = null;
                int hits = 0;
                for (var e : nameWords.entrySet()) {
                    List<String> nw = e.getValue();
                    if (nw.size() <= len || len * 3 < nw.size() * 2) continue;   // at least two thirds of the name
                    int at = 0;
                    for (String w : nw) if (at < win.size() && same(win.get(at), w)) at++;
                    if (at == win.size()) { hits++; hit = e.getKey(); }
                }
                if (hits == 1) {
                    int st = toks.get(i).start(), en = toks.get(i + len - 1).end();
                    if (found.stream().noneMatch(f -> f.start() < en && st < f.end())) found.add(new Mention(hit, st, en));
                }
            }
        found.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start()) : Integer.compare(b.end() - b.start(), a.end() - a.start()));
        List<Mention> out = new ArrayList<>();
        for (Mention m : found) {
            boolean inside = out.stream().anyMatch(o -> o.start() <= m.start() && m.end() <= o.end());
            if (!inside) out.add(m);
        }
        return out;
    }

    /**
     * A one-word product name («ثومية», «بطاطس») is often just an ingredient of another dish («بالثومية», «ثومية كبيرة، مخلل وبطاطس»): it counts as the product only with its price
     * right after it or at the start of a list line.
     */
    private static boolean singleWordProduct(List<Tok> toks, int i, String lightText) {
        Tok t = toks.get(i);
        if (t.raw().startsWith("ب") && !t.raw().equals(t.word())) return false;
        int lineStart = lightText.lastIndexOf('\n', Math.max(0, t.start() - 1)) + 1;
        if (t.start() == 0 || lightText.substring(lineStart, t.start()).matches("\\s*(?:[-•*▪✅🔸🔹]|\\d+\\s*[-.)])?\\s*")) return true;
        for (int k = i + 1; k < Math.min(toks.size(), i + 5); k++) if (toks.get(k).word().chars().allMatch(Character::isDigit)) return true;
        return i > 0 && ASKED_FOR.contains(toks.get(i - 1).word());   // «عايز ثومية كبير», «سعر ثومية», «مش بنبيع ثومية»
    }

    /** The word before a one-word product that makes it the thing asked for or sold, not an ingredient («بنضيف عليه الثومية»). */
    private static final Set<String> ASKED_FOR = Set.of("عايز", "عاوز", "عايزه", "عاوزه", "وعايز", "محتاج", "محتاجه", "سعر", "بكام", "عندكم", "اطلب", "هات", "ابعتلي",
            "بنبيع", "نبيع", "ببيع", "مبنبيعش", "مفيش", "معندناش");

    /** The text with the sentence [s0, s1) replaced, keeping one space or line break around it. */
    static String replaceSentence(String answer, int s0, int s1, String with) {
        String before = answer.substring(0, s0), after = answer.substring(s1);
        String sentence = answer.substring(s0, s1);
        String lead = !before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1)) ? " " : "";
        String tail = sentence.endsWith("\n") ? "\n" : after.isEmpty() || Character.isWhitespace(after.charAt(0)) ? "" : " ";
        return before + lead + with + tail + after;
    }

    // ---- the checks ------------------------------------------------------------------------------------------------------

    /**
     * Every problem the reply has. {@code customer} is the customer's newest message, {@code touched} the products the tools returned for this reply (one of them stands for a reply that
     * names a product loosely).
     */
    static List<Problem> check(String customer, String answer, Shop shop, List<String> touched) {
        if (answer == null || answer.isBlank()) return List.of();
        List<Problem> out = new ArrayList<>();
        var names = shop.names();
        var ms = mentions(answer, names);
        Map<String, Product> cache = new LinkedHashMap<>();
        java.util.function.Function<String, Product> load = slug -> cache.computeIfAbsent(slug, s -> { Product p = shop.product(s); return p == null ? new Product(s, "", 0, List.of()) : p; });
        // a reply that names no product («ده الجاكيت، ٨٩٠ جنيه») is about the one the tools returned, or the one the customer named
        String only = null;
        if (ms.isEmpty()) {
            if (touched != null && touched.size() == 1) only = touched.get(0);
            else if (customer != null) {
                var asked = mentions(customer, names).stream().map(Mention::slug).distinct().toList();
                if (asked.size() == 1) only = asked.get(0);
            }
        }
        Set<Integer> qty = quantitiesSaid(customer, answer);   // a total of k pieces counts only when k pieces were talked about
        final String only0 = only;
        // every check on its own: one that breaks on an odd reply never takes the others with it, and says so (CHECK_ERROR)
        run(out, () -> optionPrices(answer, ms, only0, qty, shop, load, out));
        // the products the customer or the tools named: their prices and options are right here too, whatever product a loose name in the reply points at
        Set<String> context = new LinkedHashSet<>(touched == null ? List.of() : touched);
        if (customer != null) for (Mention m : mentions(customer, names)) context.add(m.slug());
        run(out, () -> { if (out.stream().noneMatch(x -> x.kind().equals("OPTION_PRICE"))) productPrices(answer, ms, context, qty, shop, load, out); });
        run(out, () -> unitValues(answer, ms, context, load, out));
        String[] askedForeign = new String[1];
        run(out, () -> { if (customer != null) { var cm = mentions(customer, names); if (cm.size() == 1) askedForeign[0] = foreignValue(light(customer), load.apply(cm.get(0).slug()), shop, load); } });
        // «الكابل اللي طلبته (أبيض 2 متر) نفد» names no product: it is about the one the tools returned or the customer named, even when the reply names others after it
        String about = only != null ? only : touched != null && touched.size() == 1 ? touched.get(0) : null;
        if (about == null && customer != null) { var asked = mentions(customer, names).stream().map(Mention::slug).distinct().toList(); if (asked.size() == 1) about = asked.get(0); }
        final String about0 = about;
        run(out, () -> optionStock(customer, answer, ms, only0, about0, askedForeign[0], load, out));
        run(out, () -> namedDenied(customer, answer, ms, touched, shop, load, out));   // first: the product asked, with its price, says more than a list of near names
        run(out, () -> { if (out.stream().noneMatch(x -> x.kind().equals("FALSE_DENIAL"))) falseDenial(answer, shop, out); });
        run(out, () -> area(answer, shop, out));
        run(out, () -> hours(answer, shop, out));
        run(out, () -> sale(customer, answer, ms, shop, load, out));
        run(out, () -> offTopic(customer, answer, ms, shop, out));
        run(out, () -> wrongKind(customer, answer, ms, shop, out));
        run(out, () -> inventedValue(answer, ms, only0, shop, load, out));
        run(out, () -> { if (out.stream().noneMatch(x -> x.kind().startsWith("OPTION") || x.kind().equals("FALSE_DENIAL"))) otherColours(answer, ms, shop, load, out); });
        run(out, () -> quantity(customer, answer, ms, only0, shop, load, out));
        run(out, () -> inventedProducts(answer, shop, out));
        run(out, () -> { if (out.stream().noneMatch(x -> x.kind().equals("INVENTED_PRODUCTS"))) inventedBrands(answer, shop, out); });
        run(out, () -> betterMatch(customer, answer, ms, shop, out));
        run(out, () -> answerLock(customer, answer, ms, shop, load, out));
        return out;
    }

    /** A check that broke: its error is a CHECK_ERROR problem (never sent to the model, recorded for the AI health page), the other checks still run. */
    private static void run(List<Problem> out, Runnable check) {
        try { check.run(); } catch (RuntimeException e) {
            StackTraceElement at = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            out.add(new Problem("CHECK_ERROR", e + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()), null));
        }
    }

    /** The reply with every exact fix applied (the problems without one are left for the model). */
    static String fix(String customer, String answer, Shop shop, List<String> touched) {
        String t = answer;
        for (int pass = 0; pass < 4; pass++) {
            var fixable = check(customer, t, shop, touched).stream().filter(p -> p.fixed() != null && !p.fixed().equals(answer)).findFirst();
            if (fixable.isEmpty()) break;
            t = fixable.get().fixed();
        }
        return t;
    }

    // ---- 1. the price of an option ---------------------------------------------------------------------------------------

    /** English words for the usual Arabic option values, so "the large one is 140" is checked too. */
    private static final Map<String, List<String>> VALUE_WORDS = Map.of(
            "كبير", List.of("large", "big"), "عادي", List.of("regular", "normal", "small"), "سوبر", List.of("super"), "وسط", List.of("medium"), "صغير", List.of("small"), "دبل", List.of("double"));

    private static final Pattern UNIT_AFTER = Pattern.compile("^\\s*(?:-|–|قطع|قطعه|قطعتين|حته|حتت|بوصه|انش|جيجا|gb|tb|mah|ملي|مل|لتر|كيلو|جرام|جم(?!\\p{L})|سم|مم|mm|cm|w(?![a-z])|واط|وات|سنه|سنين|سنوات|شهر|شهور|اشهر|يوم|ايام|ساعه|ساعات|دقيقه|دقايق|دقائق|%|٪|مقاس|افراد|اشخاص|فرد|شخص|x|×|pieces?|pcs|inch|years?|months?|days?|hours?|minutes?|mins?|people|persons?|sizes?|سعر\\s+حراري|سعرات|سعر\\s+حرار|كالوري|calories|kcal|cal(?![a-z]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d{2,6}(?:\\.\\d{1,2})?(?![\\d])");

    private static void optionPrices(String answer, List<Mention> ms, String only, Set<Integer> qty, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        for (int[] s : sentences(answer)) {
            List<Mention> here = ms.stream().filter(m -> m.start() >= s[0] && m.start() < s[1]).toList();
            List<String[]> segments = new ArrayList<>();   // {slug, from, to}
            String carried = here.isEmpty() ? lastBefore(ms, s[0], only) : null;   // «- عادي ٨٥ جنيه» under the line that named the product
            if (carried != null) segments.add(new String[]{carried, String.valueOf(s[0]), String.valueOf(s[1])});
            for (int i = 0; i < here.size(); i++)
                segments.add(new String[]{here.get(i).slug(), String.valueOf(here.get(i).end()), String.valueOf(i + 1 < here.size() ? here.get(i + 1).start() : s[1])});
            for (String[] seg : segments) {
                Product p = load.apply(seg[0]);
                if (Integer.parseInt(seg[1]) >= Integer.parseInt(seg[2])) continue;   // a name that runs to the end of the sentence leaves nothing after it
                if (p.variants().size() < 2 || ChatService.priceByOption(asMaps(p)).isEmpty()) continue;   // no option changes the price
                checkSegment(answer, l, p, related(p, l.substring(s[0], s[1]), shop, load), Integer.parseInt(seg[1]), Integer.parseInt(seg[2]), qty, shop, load, out);
            }
        }
    }

    private static final Pattern MONEY_AFTER = Pattern.compile("^\\s*(?:جنيه|ج(?!\\p{L})|جم(?!\\p{L})|egp|le(?![a-z])|pounds?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_BEFORE = Pattern.compile("(?:ب|بـ|بسعر|سعره|سعرها|سعر|[–—:-]|for|at|costs?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_A_PRICE_BEFORE = Pattern.compile("(?:توفير|وفر|توفر|خصم|فرق|الفرق|زياده|زيادة|الشحن|شحن|التوصيل|توصيل|كود|كوبون|الطلبات|طلبات|طلب|فوق|اكتر\\s+من|اقل|الحد|مجاني|مجانا|الاجمالي|اجمالي|المجموع|الكل|total|subtotal|save|saving|delivery|shipping|over|above|minimum|coupon)[^\\d]{0,14}$", Pattern.CASE_INSENSITIVE);

    /**
     * The price lock: EVERY money amount written next to a product («تلفزيون فيستا HD – ٢٥٩٩٩ جنيه», «- كبير: ١٤٠» under its name) has to be one of that product's prices (now,
     * before a discount, a few pieces of it, or what one of its options adds). Delivery fees, coupon minimums and totals are not the product's and are left to the money check.
     * Works for any product of any shop. The fix writes the product's real prices in place of the line.
     */
    private static void productPrices(String answer, List<Mention> ms, Set<String> context, Set<Integer> qty, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        List<int[]> regions = new ArrayList<>();   // {mention index, from, to}
        for (int i = 0; i < ms.size(); i++) {
            Mention m = ms.get(i);
            int lineEnd = l.indexOf('\n', m.end());
            int to = Math.min(i + 1 < ms.size() ? ms.get(i + 1).start() : l.length(), lineEnd >= 0 ? lineEnd : l.length());
            if (to > m.end()) regions.add(new int[]{i, m.end(), to});
            // list lines right under the line that named it («- عادي ٨٥ جنيه») belong to it until the next product or a line that is not a list item
            int at = lineEnd;
            while (at >= 0 && at + 1 < l.length()) {
                int next = l.indexOf('\n', at + 1);
                int end = next < 0 ? l.length() : next;
                String line = l.substring(at + 1, end);
                final int lineFrom = at + 1, lineTo = end;
                if (!LIST_LINE.matcher(line).find() || ms.stream().anyMatch(x -> x.start() >= lineFrom && x.start() < lineTo)) break;
                if (valuesIn(line, load.apply(m.slug())).isEmpty()) break;   // only a line about one of its options («- كبير: ١١٠»), not the next kind of item («- مشروبات غازية — ٢٥»)
                regions.add(new int[]{i, at + 1, end});
                at = next;
            }
        }
        for (int[] rg : regions) {
            Mention m = ms.get(rg[0]);
            Product p = load.apply(m.slug());
            if (p.variants().isEmpty()) continue;
            java.util.TreeSet<Double> real = new java.util.TreeSet<>();
            Set<Double> ok = new java.util.HashSet<>();
            for (Variant v : p.variants()) { real.add(v.price()); ok.add(v.price()); if (v.before() > 0) { ok.add(v.before()); ok.add(v.before() - v.price()); } for (int k : qty) ok.add(v.price() * k); }
            for (Variant a : p.variants()) for (Variant b : p.variants()) if (a.price() > b.price()) ok.add(a.price() - b.price());   // «التركيب بيزود ٤٥٠»
            for (Product r : related(p, l.substring(Math.max(0, m.start() - 40), rg[2]), shop, load)) for (Variant v : r.variants()) { ok.add(v.price()); if (v.before() > 0) ok.add(v.before()); for (int k : qty) ok.add(v.price() * k); }
            for (String c : context) for (Variant v : load.apply(c).variants()) { ok.add(v.price()); if (v.before() > 0) ok.add(v.before()); }
            for (Tok t : tokens(l, rg[1], rg[2])) {
                if (!NUMBER.matcher(t.word()).matches()) continue;
                String after = l.substring(t.end(), Math.min(l.length(), t.end() + 10)), before = l.substring(Math.max(rg[1], t.start() - 24), t.start());
                if (UNIT_AFTER.matcher(after).find()) continue;   // «55 بوصة», «4 قطع»: an option, not money
                if (!MONEY_AFTER.matcher(after).find() && (!PRICE_BEFORE.matcher(before).find() || before.matches("(?s).*\\d\\s*[-–]\\s*"))) continue;   // «٥٠-١٠٠ صحن»: a range of pieces
                if (NOT_A_PRICE_BEFORE.matcher(before).find()) continue;
                double amount = Double.parseDouble(t.word());
                if (ok.contains(amount) || amount < real.first() * 0.3) continue;
                if (namedInLine(l, t.start(), amount, p, shop, load)) continue;   // «… تابلت أطفال نوفا كيدز 8 (3499)» in a line the name check read loosely
                int lineFrom = l.lastIndexOf('\n', t.start()) + 1, lineTo = l.indexOf('\n', t.end());
                if (lineTo < 0) lineTo = l.length();
                String fixed = real.size() == 1
                        ? answer.substring(0, t.start()) + num(real.first(), arabicDigits(answer.substring(t.start(), t.end()))) + answer.substring(t.end())
                        : answer.substring(0, lineFrom) + priceLine(p, arabic(answer)) + answer.substring(lineTo);
                out.add(new Problem("OPTION_PRICE", "«" + p.name() + "» never costs " + num(amount, false) + ". Its real prices: " + priceLine(p, false) + ". Say only these.", fixed));
                return;
            }
        }
    }

    private static final Pattern VALUE_WITH_UNIT = Pattern.compile("(?<![\\d.])(\\d{1,4}(?:\\.\\d{1,2})?)\\s*(بوصه|انش|جيجا|تيرا|مم|سم|سنه|سنين|سنوات|شهر|شهور|وات|قطع|قطعه|كيلو|جرام|لتر|مللي|مل|متر|افراد|gb|tb|mm|cm|inch|w)(?!\\p{L})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC_BEFORE = Pattern.compile("(?:رامات|رام|ram|شاشه|شاشته|بطاريه|بطاريته|كاميرا|معالج|كارت|ذاكره|شحن|بشحن|سرعه|عرض|طول|وزن|سمك|سماكه|بيكفي|يكفي|ضمان|لحد|حتي|من|لـ|ل|up\\s+to|screen|battery|camera|display|gpu|rtx|vram)[^\\n،,.]{0,10}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC_AFTER = Pattern.compile("^\\s*(?:رامات|رام|ram|ذاكره|vram|ddr\\d?|شاشه|بطاريه|للشاشه|ram\\b)", Pattern.CASE_INSENSITIVE);

    /**
     * The option lock for values with a unit: «تلفزيون فيستا HD ٦٥ بوصة» when that TV comes in 32 and 43 inches only, «X12 بـ 512 جيجا» when it has 128 and 256. A number with the same
     * unit as the product's own options has to be one of them (a spec like «رامات 8 جيجا» is left alone). Works for any shop.
     */
    private static void unitValues(String answer, List<Mention> ms, Set<String> context, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        for (int i = 0; i < ms.size(); i++) {
            Mention m = ms.get(i);
            int to = i + 1 < ms.size() ? ms.get(i + 1).start() : l.length();
            for (int[] sn : sentences(answer)) if (sn[0] <= m.end() && sn[1] > m.end()) to = Math.min(to, sn[1]);   // the sentence that names it
            Product p = load.apply(m.slug());
            Map<String, Set<String>> own = new LinkedHashMap<>();   // unit -> the numbers its options have with it
            for (Variant v : p.variants()) for (String val : v.options().values()) {
                Matcher u = VALUE_WITH_UNIT.matcher(light(val));
                while (u.find()) own.computeIfAbsent(unitWord(u.group(2)), k -> new java.util.HashSet<>()).add(u.group(1));
            }
            if (own.isEmpty() || to <= m.end()) continue;
            Matcher u = VALUE_WITH_UNIT.matcher(l).region(m.end(), to);
            while (u.find()) {
                Set<String> nums = own.get(unitWord(u.group(2)));
                if (nums == null || nums.contains(u.group(1))) continue;
                if (SPEC_BEFORE.matcher(l.substring(Math.max(m.end(), u.start() - 14), u.start())).find() || SPEC_AFTER.matcher(l.substring(u.end(), Math.min(l.length(), u.end() + 12))).find()) continue;
                final String said = u.group(1), unit = unitWord(u.group(2));
                final int at = u.start();
                String sentence = sentences(answer).stream().filter(sn -> sn[0] <= at && at < sn[1]).map(sn -> l.substring(sn[0], sn[1])).findFirst().orElse("");
                if (NEG_STRICT.matcher(sentence).find() || STOCK_NEG.matcher(sentence).find() || DENY.matcher(sentence).find()) continue;   // «مش متاح «16 جيجا»»: said no to it
                if (context.stream().filter(c -> !c.equals(m.slug())).anyMatch(c -> load.apply(c).variants().stream().flatMap(v -> v.options().values().stream())
                        .anyMatch(val -> { Matcher x = VALUE_WITH_UNIT.matcher(light(val)); while (x.find()) if (x.group(1).equals(said) && unitWord(x.group(2)).equals(unit)) return true; return false; }))) continue;   // the product the customer means has it
                Set<String> vals = new LinkedHashSet<>();
                for (Variant v : p.variants()) for (String val : v.options().values()) if (VALUE_WITH_UNIT.matcher(light(val)).find()) vals.add(val);
                int lineFrom = l.lastIndexOf('\n', u.start()) + 1, lineTo = l.indexOf('\n', u.end());
                if (lineTo < 0) lineTo = l.length();
                String truth = arabic(answer) ? "«" + p.name() + "» متاح في: " + String.join("، ", vals) + " بس." : p.name() + " comes in " + String.join(", ", vals) + " only.";
                out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» does not come in «" + u.group().trim() + "»; it comes in " + String.join("، ", vals) + " only. Say only those.",
                        answer.substring(0, lineFrom) + truth + answer.substring(lineTo)));
                return;
            }
        }
    }

    private static String unitWord(String u) {
        return switch (u.toLowerCase(java.util.Locale.ROOT)) { case "انش", "inch" -> "بوصه"; case "gb" -> "جيجا"; case "tb" -> "تيرا"; case "mm" -> "مم"; case "cm" -> "سم"; case "سنين", "سنوات" -> "سنه";
            case "w" -> "وات"; case "قطعه" -> "قطع"; case "شهور" -> "شهر"; case "مل" -> "مللي"; default -> u; };
    }

    /** Another product of the shop costs exactly this amount and its own words (or one word of letters only it has) are in the same line: the amount is that product's. */
    private static boolean namedInLine(String l, int at, double amount, Product named, Shop shop, java.util.function.Function<String, Product> load) {
        int from = l.lastIndexOf('\n', at) + 1, to = l.indexOf('\n', at);
        String line = l.substring(from, to < 0 ? l.length() : to);
        Set<String> said = new java.util.HashSet<>();
        for (Tok t : tokens(line, 0, line.length())) { said.add(t.word()); said.add(stem(t.word())); }
        Set<String> own = new java.util.HashSet<>();
        for (Tok t : tokens(light(named.name()), 0, named.name().length())) own.add(t.word());
        Set<String> ownValues = new java.util.HashSet<>();   // «أبيض», «أوبن بوكس»: its own options are no other product's name
        for (Variant v : named.variants()) for (String val : v.options().values()) for (Tok t : tokens(light(val), 0, val.length())) ownValues.add(t.word());
        for (Named n : shop.names()) {
            if (n.slug().equals(named.slug())) continue;
            // its own words, not the ones it shares with the product named («تلفزيون فيستا» is in both TVs; «qled» says which)
            List<String> distinct = n.words().stream().filter(w -> !own.contains(w) && !STOP.contains(w) && w.length() >= 2).toList();
            // all of them, or one word of letters that only it has («الشاحن اللاسلكي (499)» is «شاحن لاسلكي فولتا 15 وات», whose price that is)
            if (distinct.isEmpty() || !distinct.stream().allMatch(w -> said.contains(w) || said.contains(stem(w)))
                    && distinct.stream().noneMatch(w -> w.length() >= 4 && w.chars().allMatch(Character::isLetter) && !ownValues.contains(w) && (said.contains(w) || said.contains(stem(w))))) continue;
            Product p = load.apply(n.slug());
            for (Variant v : p.variants()) if (v.price() == amount || v.before() == amount) return true;
        }
        return false;
    }

    static final Map<String, Integer> NUMBER_WORDS =Map.ofEntries(Map.entry("اتنين", 2), Map.entry("الاتنين", 2), Map.entry("اثنين", 2), Map.entry("تلاته", 3), Map.entry("التلاته", 3),
            Map.entry("ثلاثه", 3), Map.entry("اربعه", 4), Map.entry("الاربعه", 4), Map.entry("خمسه", 5), Map.entry("سته", 6), Map.entry("سبعه", 7), Map.entry("تمانيه", 8), Map.entry("تسعه", 9),
            Map.entry("عشره", 10), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5));

    /** The numbers of pieces talked about in the customer's message and the reply («٣ قطع», «×2», «الاتنين», «قميصين»), so a total of that many is a right amount. */
    static Set<Integer> quantitiesSaid(String customer, String answer) {
        Set<Integer> out = new java.util.TreeSet<>();
        String t = light((customer == null ? "" : customer) + " \n " + (answer == null ? "" : answer));
        Matcher m = Pattern.compile("(?<![\\d.,])(\\d{1,3})(?![\\d.,])").matcher(t);
        while (m.find()) { int k = Integer.parseInt(m.group(1)); if (k >= 2 && k <= 100) out.add(k); }
        for (Tok w : tokens(t, 0, t.length())) {
            Integer k = NUMBER_WORDS.get(w.word());
            if (k != null) out.add(k);
            if (w.word().length() >= 5 && (w.word().endsWith("ين") || w.word().endsWith("تين"))) out.add(2);   // «قميصين», «ساندوتشين»
        }
        return out;
    }

    /** The product's real prices written by code: one price, or the price of each option that changes it («عادي ٨٥، كبير ١١٠»), or the price of each combination of them. */
    static String priceLine(Product p, boolean arabic) {
        String cur = arabic ? " جنيه" : " EGP";
        java.util.TreeSet<Double> all = new java.util.TreeSet<>();
        for (Variant v : p.variants()) all.add(v.price());
        if (all.size() == 1) return "«" + p.name() + "» " + (arabic ? "بـ " : "") + num(all.first(), false) + cur;
        // the options whose values change the price
        List<String> keys = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Variant v : p.variants()) names.addAll(v.options().keySet());
        for (String k : names) {
            Map<String, Set<Double>> by = new LinkedHashMap<>();
            for (Variant v : p.variants()) if (v.options().containsKey(k)) by.computeIfAbsent(v.options().get(k), x -> new java.util.TreeSet<>()).add(v.price());
            Set<Double> mins = new java.util.HashSet<>();
            for (var e : by.values()) mins.add(((java.util.TreeSet<Double>) e).first());
            if (mins.size() > 1) keys.add(k);
        }
        Map<String, Double> combos = new LinkedHashMap<>();
        for (Variant v : p.variants()) {
            List<String> vals = new ArrayList<>();
            for (String k : keys) if (v.options().containsKey(k)) vals.add(v.options().get(k));
            combos.merge(String.join(" ", vals), v.price(), Math::min);
        }
        if (keys.isEmpty() || combos.size() > 8)
            return "«" + p.name() + "» " + (arabic ? "من " + num(all.first(), false) + " لحد " + num(all.last(), false) : "from " + num(all.first(), false) + " to " + num(all.last(), false)) + cur + (arabic ? " حسب " : " by ") + String.join(arabic ? " و" : " and ", keys.isEmpty() ? names : keys);
        List<String> parts = new ArrayList<>();
        for (var e : combos.entrySet()) parts.add(e.getKey() + " " + num(e.getValue(), false));
        return "«" + p.name() + "»: " + String.join("، ", parts) + cur;
    }

    /**
     * Products whose name holds this one's and whose extra words are in the sentence: «ساندوتش شاورما فراخ عندنا، والوجبة بـ145» also speaks of «وجبة ساندوتش شاورما فراخ», so its
     * prices are right there too.
     */
    private static List<Product> related(Product p, String lightSentence, Shop shop, java.util.function.Function<String, Product> load) {
        List<String> own = new ArrayList<>();
        for (Tok t : tokens(light(p.name()), 0, p.name().length())) own.add(t.word());
        Set<String> said = new java.util.HashSet<>();
        for (Tok t : tokens(lightSentence, 0, lightSentence.length())) { said.add(t.word()); said.add(stem(t.word())); }
        List<Product> out = new ArrayList<>();
        for (Named n : shop.names()) {
            if (n.slug().equals(p.slug())) continue;
            List<String> nw = new ArrayList<>();
            for (Tok t : tokens(light(n.name()), 0, n.name().length())) nw.add(t.word());
            if (!nw.containsAll(own) || nw.size() == own.size()) continue;
            List<String> extra = new ArrayList<>(nw);
            extra.removeAll(own);
            if (extra.stream().allMatch(w -> said.contains(w) || said.contains(stem(w)))) out.add(load.apply(n.slug()));
        }
        return out;
    }

    /** The product named last before {@code pos} (a list line under it is about it), else {@code only}. */
    private static String lastBefore(List<Mention> ms, int pos, String only) {
        String slug = only;
        for (Mention m : ms) if (m.start() < pos) slug = m.slug();
        return slug;
    }

    private static List<Map<String, Object>> asMaps(Product p) {
        List<Map<String, Object>> vs = new ArrayList<>();
        for (Variant v : p.variants()) {
            List<String> kv = new ArrayList<>();
            v.options().forEach((k, val) -> kv.add(k + ": " + val));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("options", kv.isEmpty() ? "default" : String.join(", ", kv));
            m.put("price", v.price());
            if (v.before() > 0) m.put("priceBeforeDiscount", v.before());
            m.put("available", v.available());
            vs.add(m);
        }
        return vs;
    }

    /** The option values of a product as word sequences (bare words), each with the value it stands for. */
    private static Map<List<String>, String> valueWords(Product p) {
        Map<List<String>, String> out = new LinkedHashMap<>();
        for (Variant v : p.variants())
            for (String val : v.options().values()) {
                List<String> w = new ArrayList<>();
                for (Tok t : tokens(light(val), 0, val.length())) w.add(t.word());
                if (!w.isEmpty()) out.putIfAbsent(w, val);
                for (String en : VALUE_WORDS.getOrDefault(bare(light(val)).replace("\u200C", ""), List.of())) out.putIfAbsent(List.of(en), val);
            }
        return out;
    }

    /** A word of the text is this word, also with a joined «و» or «ب» in front («وكبير», «بصاج»). */
    static boolean same(String tok, String w) {
        String a = unit(tok), b = unit(w);
        return a.equals(b) || (a.length() == b.length() + 1 && (a.charAt(0) == 'و' || a.charAt(0) == 'ب' || a.charAt(0) == 'ل' && b.length() >= 3) && a.endsWith(b));   // «لآيفون 15»
    }

    /** Singular and plural of the units sizes are written in: «١٠ سنين» is the size «10 سنة». */
    private static String unit(String w) {
        return switch (w) { case "سنين", "سنوات", "سنه" -> "سنه"; case "شهور", "اشهر", "شهر" -> "شهر"; case "بوصات" -> "بوصه"; default -> w; };
    }

    /** The option value starting at token {@code i} (the longest one), as {value, number of words}, or null. */
    private static Object[] valueAt(List<Tok> toks, int i, Map<List<String>, String> values) {
        String matched = null;
        int len = 0;
        for (var e : values.entrySet()) {
            List<String> w = e.getKey();
            if (i + w.size() > toks.size() || w.size() <= len) continue;
            boolean all = true;
            for (int k = 0; k < w.size() && all; k++) all = same(toks.get(i + k).word(), w.get(k));
            if (all) { matched = e.getValue(); len = w.size(); }
        }
        return matched == null ? null : new Object[]{matched, len};
    }

    private static final Set<String> CURRENCY = Set.of("جنيه", "ج", "جم", "egp", "le", "pounds", "pound");

    private static void checkSegment(String answer, String l, Product p, List<Product> alsoMeant, int from, int to, Set<Integer> qty, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        var values = valueWords(p);
        List<Tok> toks = tokens(l, from, to);
        // walk the segment: option values set the context and the amount after them is their price («كبير ١١٠»), or an amount comes first and names its option after it
        // («٩٥ جنيه لقطعتين», «70 جنيه (٦ قطع)»)
        Set<String> seen = new LinkedHashSet<>();
        boolean priced = false;
        String aheadTaken = null;
        for (int i = 0; i < toks.size(); i++) {
            Object[] v = valueAt(toks, i, values);
            if (v != null) {
                String matched = (String) v[0];
                if (i > 0 && RANGE_WORDS.contains(toks.get(i - 1).word())) { i += (int) v[1] - 1; continue; }   // «تخزين لحد 2 تيرا»: the top of a range, not the option priced
                if (matched.equals(aheadTaken)) { seen.clear(); seen.add(matched); priced = true; aheadTaken = null; i += (int) v[1] - 1; continue; }
                if (priced) { seen.clear(); priced = false; }
                // «(ربع/نص كيلو)»: the bare first word of another value just before this one counts as that value too
                if (i > 0) for (var e : values.entrySet()) if (e.getKey().size() > 1 && same(toks.get(i - 1).word(), e.getKey().get(0)) && !e.getValue().equals(matched)) seen.add(e.getValue());
                seen.add(matched);
                aheadTaken = null;
                i += (int) v[1] - 1;
                continue;
            }
            Tok t = toks.get(i);
            if (!NUMBER.matcher(t.word()).matches()) continue;
            if (UNIT_AFTER.matcher(l.substring(t.end(), Math.min(l.length(), t.end() + 14))).find()) continue;
            if (l.substring(Math.max(from, t.start() - 3), t.start()).matches(".*[x×]\\s*$")) continue;
            // «٥٥ بوصة بـ ٢٠٤٩٩ (بدون تركيب)»: a value right after the amount is that amount's, with the other options said before it
            String ahead = valueAhead(answer, toks, i + 1, values);
            if (ahead == null && (seen.isEmpty() || priced)) continue;   // «… أو ٢٠٩٤٩ (مع التركيب)»: a second amount is not the same option's price
            Set<String> context = new LinkedHashSet<>(seen);
            if (ahead != null) {
                context.removeIf(c -> p.variants().stream().noneMatch(x -> x.options().values().containsAll(new java.util.HashSet<>(List.of(c, ahead)))));
                context.add(ahead);
            }
            if (ahead != null) aheadTaken = ahead;
            priced = true;
            double amount = Double.parseDouble(t.word());
            List<Variant> fit = p.variants().stream().filter(x -> x.options().values().containsAll(context)).toList();
            if (fit.isEmpty()) continue;
            Set<Double> ok = new java.util.HashSet<>();
            for (Variant x : fit) { ok.add(x.price()); if (x.before() > 0) ok.add(x.before()); for (int k : qty) ok.add(x.price() * k); }
            for (Product r : alsoMeant) for (Variant x : r.variants()) { ok.add(x.price()); if (x.before() > 0) ok.add(x.before()); }
            if (ok.contains(amount)) continue;
            if (namedInLine(l, t.start(), amount, p, shop, load)) continue;   // «الطقم … شاحن 20 وات + كابل … بـ 549»: the bundle's price, not the cable's
            java.util.TreeSet<Double> real = new java.util.TreeSet<>();
            for (Variant x : fit) real.add(x.price());
            if (amount < real.first() * 0.3 || amount > real.last() * 3) continue;   // «15.6 بوصة», «120 هرتز»: not a price
            if (real.size() != 1) {   // the values said do not pin one price: name the real ones, the rewrite picks
                int lineFrom = answer.lastIndexOf('\n', t.start()) + 1, lineTo = answer.indexOf('\n', t.end());
                if (lineTo < 0) lineTo = answer.length();
                String byLine = answer.substring(0, lineFrom) + priceLine(p, arabic(answer)) + answer.substring(lineTo);   // the line goes, the real prices are written in its place
                if (real.size() <= 4) out.add(new Problem("OPTION_PRICE", "«" + p.name() + "» " + String.join(" ", context) + " costs " + String.join(" or ", real.stream().map(r -> num(r, false)).toList()) + " (by the other options), never " + num(amount, false) + ".", byLine));
                return;
            }
            double right = real.iterator().next();
            String what = "«" + p.name() + "» " + String.join(" ", context);
            String fixed = answer.substring(0, t.start()) + num(right, arabicDigits(answer.substring(t.start(), t.end()))) + answer.substring(t.end());
            out.add(new Problem("OPTION_PRICE", what + " costs " + num(right, false) + ", not " + num(amount, false) + ". Say exactly " + num(right, false) + ".", fixed));
            return;
        }
    }

    private static final Set<String> RANGE_WORDS = Set.of("لحد", "حتي", "لغايه", "ل", "لـ", "up", "to");

    private static final Set<String> FILLER_AHEAD = Set.of("جنيه", "ج", "جم", "egp", "le", "pounds", "pound", "ل", "لل", "for", "each", "the", "a", "سعه", "السعه", "مقاس", "المقاس", "حجم", "الحجم", "لون", "اللون", "نسخه", "النسخه", "size", "storage");

    /**
     * The option value a price is for when it comes right after it, past «جنيه» or «لـ» («90 جنيه لـ٣ قطع», «70 جنيه (٦ قطع)»), or null. With nothing between, the next value starts
     * the next pair («عادي ١٢٥ / كبير ١٦٠»).
     */
    private static String valueAhead(String answer, List<Tok> toks, int from, Map<List<String>, String> values) {
        int fillers = 0;
        for (int i = from; i < Math.min(toks.size(), from + 4); i++) {
            Object[] v = valueAt(toks, i, values);
            if (v != null) {
                int after = i + (int) v[1];
                boolean ownPrice = after < toks.size() && NUMBER.matcher(toks.get(after).word()).matches();   // «٨٥ ج (كبير ١١٠)»: that value has its own price
                boolean inBrackets = fillers == 0 && from > 0 && answer.substring(toks.get(from - 1).end(), toks.get(i).start()).contains("(");   // «10999 (256 جيجا)»
                return (fillers > 0 || inBrackets) && !ownPrice ? (String) v[0] : null;
            }
            if (!FILLER_AHEAD.contains(toks.get(i).word())) return null;
            fillers++;
        }
        return null;
    }

    // ---- 2. colours and sizes of one product ----------------------------------------------------------------------------

    /** A real negation: «للأسف» alone only softens («للأسف متوفر منه ٢ قطعة بس»). */
    static final Pattern NEG_STRICT = Pattern.compile("(?:(?<!\\p{L})(?:و|ف)?(?:مش|مفيش|مافيش|مفيهوش|مفيهاش|مافيهوش|مافيهاش|مفيناش|مافيناش|معندناش|ماعندناش|معنديش|مبقاش|متوفرش|موجودش|متاحش|خلص|خلصان|خلصانه|خلصت|خلصوا|نفذ|نفذت|نفد|نفدت|نافد|نفاذ|نافذ|ناضب|نفاص|ملهوش|ملهاش|مالوش|مجيش|مجيهش|مجاش|مبيجيش|مابيجيش|مبييجيش|مابييجيش|ميجيش|مبتجيش|مبيجيش)(?!\\p{L})"
            + "|(?<!\\p{L})ليس(?!\\p{L})|(?<!\\p{L})غير\\s+(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده)|(?<!\\p{L})لا\\s+يوجد|not\\s+available|out\\s+of\\s+stock|sold\\s+out|unavailable|don'?t\\s+have|do\\s+not\\s+have|isn'?t|aren'?t|no\\s+longer)", Pattern.CASE_INSENSITIVE);

    /** A word that says it is not there, as a whole word («بوليستر» holds «ليس» and says nothing), with «و»/«ف» in front allowed. */
    static final Pattern NEG = Pattern.compile("(?:(?<!\\p{L})(?:و|ف)?(?:مش|مفيش|مافيش|مفيهوش|مفيهاش|مافيهوش|مافيهاش|معندناش|ماعندناش|معنديش|مبقاش|متوفرش|موجودش|متاحش|خلص|خلصان|خلصانه|خلصت|خلصوا|نفذ|نفذت|نفد|نفدت|نافد|نفاذ|نافذ|ناضب|نفاص|للاسف|ملهوش|ملهاش|مالوش|مجيش|مجيهش|مجاش|مبيجيش|مابيجيش|مبييجيش|مابييجيش|ميجيش|مبتجيش)(?!\\p{L})|(?<!\\p{L})ليس(?!\\p{L})"
            + "|(?<!\\p{L})غير\\s+(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده)|(?<!\\p{L})لا\\s+يوجد|not\\s+available|out\\s+of\\s+stock|sold\\s+out|unavailable|don'?t\\s+have|do\\s+not\\s+have|isn'?t|aren'?t|no\\s+longer)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAIL = Pattern.compile("(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده|عندنا|بالوان|الوان|الالوان|المقاسات|مقاسات|available|in\\s+stock|comes?\\s+in|colou?rs|sizes)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_SIZES = Pattern.compile("(?<!\\p{L})(?:ب|في\\s+|ف)?(?:كل|جميع)\\s+(?:ال)?(?:مقاسات|المقاسات|الالوان|الوان)|(?:ال)?(?:مقاسات|الوان)\\s+(?:كلها|كلهم)|all\\s+(?:the\\s+)?(?:sizes|colou?rs)|every\\s+size", Pattern.CASE_INSENSITIVE);
    /** Words that offer the item as ready to sell («تحب أحجزهولك؟», «موجود», "available"). */
    private static final Pattern OFFER = Pattern.compile("(?:احجز|أحجز|نحجز|اجهز|أجهز|نجهز|ابعتهولك|متاح|متاحه|متوفر|متوفره|موجود|موجوده|available|in\\s+stock|shall\\s+i\\s+(?:book|reserve|order))", Pattern.CASE_INSENSITIVE);
    /** A stock count («١٦ قطعة في المخزن», «فاضل ٣ بس», "6 left"); a number that is part of a name or option («وجبة بروستد ٤ قطع») is not one. */
    private static final Pattern COUNT = Pattern.compile("(?:(\\d{1,4})\\s*(?:قطعه|قطع|حته|حتت)\\s*(?:في\\s+المخزن|بالمخزن|متاحه|متاحين|متوفره|متوفرين|موجوده|موجودين|متبقيه|باقيه|فاضله)|(?:فاضل|باقي|متبقي|فيه|في|متوفر|متاح|موجود|عندنا|متوفر\\s+عندنا|عندنا\\s+متوفر)\\s+(?:منه\\s+|منها\\s+)?(?:بـ\\s*|ب\\s*)?(\\d{1,4})\\s*(?:قطعه|قطع|حته|حتت|بس|اجهزه|جهاز)|(\\d{1,4})\\s*(?:pieces?\\s+)?(?:in\\s+stock|left|available)|(?:الكميه|كميه|المخزون|المتاح|المتاحه)[^.؟?!\\n\\d]{0,50}?(\\d{1,4})\\s*(?:قطعه|قطع|حته|حتت))", Pattern.CASE_INSENSITIVE);
    /** A stock this high is a kitchen's or a made-to-order shop's placeholder, not a count to tell the customer. */
    static final int MADE_TO_ORDER = 100;
    /** «قطعة واحدة», «قطعتين», «تلات قطع»…: the numbers 1 to 10 in words, as {@link #light} writes them. */
    private static final List<Pattern> STOCK_WORDS = List.of(Pattern.compile("$^"),
            Pattern.compile("(?:قطعه|حته|واحده|واحد)\\s+(?:بس|فقط|واحده|واحد)|اخر\\s+(?:قطعه|حته)|(?<!\\p{L})واحده\\s+بس"),
            Pattern.compile("(?<!\\p{L})(?:قطعتين|حتتين|اتنين|اثنين|الاتنين|الاثنين|التنين)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:تلات|تلاته|ثلاث|ثلاثه|التلاته|الثلاثه|التلات)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:اربع|اربعه|الاربعه|الاربع)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:خمس|خمسه|الخمسه|الخمس)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:سته|السته)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:سبع|سبعه|السبعه|السبع)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:تمانيه|ثمانيه|التمانيه|الثمانيه)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:تسع|تسعه|التسعه|التسع)(?!\\p{L})"),
            Pattern.compile("(?<!\\p{L})(?:عشر|عشره|العشره|العشر)(?!\\p{L})"));
    private static final Set<String> SIZE_CODES = Set.of("xs", "s", "m", "l", "xl", "xxl", "3xl", "xxxl");

    private static void optionStock(String customer, String answer, List<Mention> ms, String only, String about, String askedForeign, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        boolean deniedBefore = false;   // after «خلص» the next sentences offer other options: the customer's own choice is not read into them
        for (int[] s : sentences(answer)) {
            String said0 = deniedBefore ? null : customer;
            if (NEG_STRICT.matcher(l.substring(s[0], s[1])).find()) deniedBefore = true;   // «للأسف نسخة الـ ٥١٢ خلصت» names no product but still says it
            List<Mention> here = ms.stream().filter(m -> m.start() >= s[0] && m.start() < s[1]).toList();
            boolean carried = here.isEmpty();
            String prior = lastBefore(ms, s[0], only);
            boolean fromContext = carried && prior == null && about != null;   // no product named yet in the reply: the one of this chat
            String slug = here.size() == 1 ? here.get(0).slug() : carried ? (prior != null ? prior : about) : null;
            if (slug == null) continue;
            Product p = load.apply(slug);
            if (p.variants().isEmpty()) continue;
            String sentence = l.substring(s[0], s[1]);
            Map<String, String> said = valuesIn(sentence, p);   // value -> option name
            boolean neg = NEG.matcher(sentence).find();
            if (askedForeign != null && sentence.contains(light(askedForeign).split(" ")[0]) && neg) continue;   // «مش هتناسب آيفون 14 برو»: about the model it does not fit
            // a denial is checked only for the product named in the same sentence (a list line under another product may deny something else)
            if (neg && (!carried || only != null || fromContext)) {
                String problem = deniedButInStock(p, sentence, said0);
                if (problem != null) {
                    // still denied after the rewrite: that sentence is replaced by what is really in stock
                    String truth = arabic(answer) ? "«" + p.name() + "» متاح عندنا ✅ المتاح منه: " + stockLine(p, Map.of()) + "." : p.name() + " is available ✅ In stock: " + stockLine(p, Map.of()) + ".";
                    out.add(new Problem("OPTION_STOCK", problem, (answer.substring(0, s[0]) + truth + (answer.substring(s[1]).startsWith("\n") ? "" : "\n") + answer.substring(s[1])).trim()));
                    continue;
                }
            }
            if (!neg) {
                String combo = soldOutCombo(p, sentence, said0, l);
                if (combo != null) {
                    // still offered after the rewrite: that sentence is replaced by the real stock, written by code
                    Map<String, String> want = wanted(p, sentence, said0);
                    String truth = arabic(answer)
                            ? "«" + p.name() + "» " + String.join(" ", want.keySet()) + " خلص حالياً 🙏 المتاح منه: " + stockLine(p, Map.of()) + "."
                            : p.name() + " in " + String.join(" ", want.keySet()) + " is sold out right now 🙏 In stock: " + stockLine(p, Map.of()) + ".";
                    // the rest of such a reply still offers it («أيوة متوفر 👍 … - اللون: أزرق (متاح)»): the whole reply is the truth, with one question
                    out.add(new Problem("OPTION_STOCK", combo, truth + (arabic(answer) ? " تحب أجهزلك واحد من المتاح؟" : " Shall I get one of these ready for you?")));
                    continue;
                }
                String count = wrongCount(p, sentence, said0);
                if (count != null) { out.add(new Problem("OPTION_STOCK", count, null)); continue; }
            }
            if (!neg && ALL_SIZES.matcher(sentence).find()) {
                // the clause that says it («وكمان أزرق وأسود بكل المقاسات»), with the colours it names, not the ones of the clause before
                String clause = sentence;
                for (String c : sentence.split("[،,؛;]")) if (ALL_SIZES.matcher(c).find()) { clause = c; break; }
                Map<String, String> inClause = valuesIn(clause, p);
                String missing = missingCombos(p, inClause.isEmpty() ? said : inClause);
                if (missing != null) { out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» is NOT in every size and colour: " + missing + ". Say exactly which are in stock.", null)); continue; }
            }
            if (!neg && AVAIL.matcher(sentence).find()) {
                Set<String> realSizes = new LinkedHashSet<>();
                Set<String> shownSizes = new LinkedHashSet<>();
                for (Variant v : p.variants()) v.options().forEach((k, val) -> { if (sizeOption(k)) { realSizes.add(bare(light(val)).replace("\u200C", "")); shownSizes.add(val); } });
                // only the size codes said right after the product's name (another product named later in the sentence has its own sizes)
                int from = carried ? s[0] : here.get(0).end();
                List<Tok> after = tokens(l, from, s[1]);
                List<String> invented = new ArrayList<>();
                for (int i = 0; i < Math.min(after.size(), 14) && !realSizes.isEmpty(); i++) {   // a product without sizes still gets the sold-out check below
                    Tok t = after.get(i);
                    if (SIZE_CODES.contains(t.word()) && !realSizes.contains(t.word()) && answer.substring(t.start(), t.end()).equals(answer.substring(t.start(), t.end()).toUpperCase(Locale.ROOT)))
                        invented.add(t.word().toUpperCase(Locale.ROOT));
                }
                List<String> soldOut = new ArrayList<>();
                for (var e : said.entrySet())   // any option: a colour, a size, a model it fits, a capacity…
                    if (p.variants().stream().noneMatch(v -> v.available() > 0 && v.options().containsValue(e.getKey()))) soldOut.add(e.getKey());
                if (!invented.isEmpty()) out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» does not come in " + String.join("، ", invented) + "; its sizes are " + String.join("، ", shownSizes) + ". Say only those.", null));
                else if (!soldOut.isEmpty() && said.size() == soldOut.size()) {
                    String truth = arabic(answer) ? "«" + p.name() + "» " + String.join(" ", soldOut) + " خلص حالياً 🙏 المتاح منه: " + stockLine(p, Map.of()) + "."
                                                  : p.name() + " in " + String.join(" ", soldOut) + " is sold out right now 🙏 In stock: " + stockLine(p, Map.of()) + ".";
                    out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» " + String.join(" ", soldOut) + " is SOLD OUT right now: " + stockLine(p, Map.of()) + ".",
                            (answer.substring(0, s[0]) + truth + (answer.substring(s[1]).startsWith("\n") ? "" : "\n") + answer.substring(s[1])).trim()));
                }
            }
        }
    }

    /** The values of {@code p} said in the sentence, with a colour or size it leaves out taken from the customer's message («جاكيت بافر ولادي احمر مقاس ١٠» → «تحب أحجزهولك؟»). */
    private static Map<String, String> wanted(Product p, String sentence, String customer) {
        Map<String, String> want = new LinkedHashMap<>(valuesIn(sentence, p));
        if (customer != null) for (var e : valuesIn(light(customer), p).entrySet()) if (!want.containsValue(e.getValue())) want.put(e.getKey(), e.getValue());
        return want;
    }

    /** A colour and a size (or two options) offered together that are sold out together: the red size 10 offered when only other sizes of red are left. Null when fine. */
    /** «ولا لون تاني بمقاس L؟», «أبلّغك أول ما البني M يرجع»: other choices, or the day it is back. */
    private static final Pattern ALTERNATIVE = Pattern.compile("(?:(?<!\\p{L})(?:تاني|تانيه|تانين|غيره|غيرها|بدل|بدلها|بديل|بدايل|يرجع|ترجع|يتوفر|تتوفر|ينزل|تنزل|يوصل|توصل|توصل|يجي|ابلغك|ابلّغك|هبلغك|نبلغك|هنبلغك)(?!\\p{L})|another|other|instead|back\\s+in\\s+stock|restock)", Pattern.CASE_INSENSITIVE);

    private static String soldOutCombo(Product p, String sentence, String customer, String reply) {
        if (!OFFER.matcher(reply).find()) return null;   // «متوفرين» may sit in the sentence before the one naming the product
        if (ALTERNATIVE.matcher(sentence).find()) return null;
        Map<String, String> want = wanted(p, sentence, customer);
        if (new java.util.HashSet<>(want.values()).size() < 2) return null;   // one option alone is the simpler check below
        List<Variant> exact = p.variants().stream().filter(v -> v.options().values().containsAll(want.keySet())).toList();
        if (exact.isEmpty() || exact.stream().anyMatch(v -> v.available() > 0)) return null;
        if (NEG.matcher(sentence).find()) return null;   // this sentence says it is not available: the denial checks look at that
        return "«" + p.name() + "» " + String.join(" ", want.keySet()) + " is SOLD OUT (0 in stock). Never offer it; in stock: " + stockLine(p, want) + ". Say it is not available in that and offer what is.";
    }

    /** «فيه ١٦ قطعة» when the stock of that exact option is another number. Null when fine or when the option is not pinned to one variant. */
    private static String wrongCount(Product p, String sentence, String customer) {
        Matcher m = COUNT.matcher(sentence);
        if (!m.find()) return null;
        Map<String, String> want = wanted(p, sentence, customer);
        if (want.isEmpty()) return null;
        List<Variant> exact = p.variants().stream().filter(v -> v.options().values().containsAll(want.keySet())).toList();
        if (exact.size() != 1) return null;
        String num = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        if (want.keySet().stream().anyMatch(v -> light(v).matches(num + "\\s*(?:قطع|قطعه|حته|حتت|pieces?).*"))) return null;   // «ناجتس ٦ قطع متاح»: the pack, not the stock
        int said = Integer.parseInt(num), real = exact.get(0).available();
        if (said == real || real <= 0 || real >= MADE_TO_ORDER) return null;
        return "«" + p.name() + "» " + String.join(" ", want.keySet()) + " has " + real + " in stock, not " + said + ". Say " + real + " or just that it is available.";
    }

    /**
     * «مش باللون الزيتي»: the clause that denies names values the product HAS in stock together. The denial must sit next to a value (not «٦ قطع مع كاتشب (مش حار)»); a colour or
     * size the product does not come in at all makes the denial right («الأسود مقاس L مش متوفر» for a shirt without black); a size or colour the clause leaves out is taken from the
     * rest of the sentence, then from the customer's message («الذهبي غير متوفر» after «مقاس ٣٨»).
     */
    private static final Pattern STOCK_NEG = Pattern.compile("(?:(?<!\\p{L})(?:و|ف)?(?:مفيش|مافيش|مفيهوش|مفيهاش|مافيهوش|مافيهاش|مفيناش|مافيناش|معندناش|ماعندناش|مبقاش|متوفرش|موجودش|متاحش|خلص|خلصان|خلصانه|خلصت|خلصوا|نفذ|نفذت|نفد|نفدت|نافد|نفاذ|نافذ|ناضب|نفاص|مش\\s+(?:لاقي|لاقيه|لقيت|لاقيين)|ملقتش|مالقيتش|ملقيتش|ملاقيتش|مالقتش|ملهوش|ملهاش|مالوش|مجيش|مجيهش|مجاش|مبيجيش|مابيجيش|مبييجيش|مابييجيش|ميجيش|مبتجيش)(?!\\p{L})"
            + "|(?<!\\p{L})(?:و|ف)?(?:مش|غير|ليس|لا)\\s+(?:(?:هو|هي|ده|دي)\\s+)?(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده|عندنا|فيه|فيها|بيجي|بتيجي|جاي|جايه|بييجي|بنبيع|بنوفر|يوجد|available)(?!\\p{L})"
            + "|not\\s+available|out\\s+of\\s+stock|sold\\s+out|unavailable|don'?t\\s+have|do\\s+not\\s+have|no\\s+longer)", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCEPT = Pattern.compile("(?<!\\p{L})(?:مفيش|مافيش|مفيهوش|مفيهاش|ملوش|مالوش|ملهاش|مش\\s+\\p{L}+)\\s+(?:غير|الا|سوي)(?!\\p{L})");
    private static final Pattern PLAIN_AVAIL = Pattern.compile("(?<!\\p{L})(?:و|ف)?(?:متاح|متاحه|متاحين|متوفر|متوفره|متوفرين|موجود|موجوده|available)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    private static String deniedButInStock(Product p, String sentence, String customer) {
        Set<String> valueWords = new java.util.HashSet<>(List.of("اللون", "لون", "المقاس", "مقاس", "في", "ف"));
        for (Variant v : p.variants()) for (String val : v.options().values()) for (Tok t : tokens(light(val), 0, val.length())) valueWords.add(t.word());
        for (String clause0 : sentence.split("[،,؛;]")) {
            if (EXCEPT.matcher(clause0).find()) continue;   // «مفيهوش غير اللون الأبيض», «مش متاح غير S»: only that, not a denial of it
            String clause = clause0;
            // «ده مش متوفر حالياً 😅 الوردي متاح بس مقاس آيفون 14 برو»: what comes after a plain «متاح» is offered, not denied
            Matcher ng = STOCK_NEG.matcher(clause);
            if (ng.find()) {
                Matcher av = PLAIN_AVAIL.matcher(clause).region(ng.end(), clause.length());
                while (av.find()) {
                    String before = clause.substring(Math.max(0, av.start() - 5), av.start());
                    if (before.matches("(?s).*(?:مش|غير)\\s*$")) continue;
                    clause = clause.substring(0, av.start());
                    break;
                }
            }
            List<Tok> ct = tokens(clause, 0, clause.length());
            boolean weakOnValue = false;   // «مش باللون الزيتي», «مش في XXL»: «مش» right before one of its values
            for (int i = 0; i + 1 < ct.size(); i++)
                if (Set.of("مش", "غير", "ومش", "فمش").contains(ct.get(i).word()) && (valueWords.contains(ct.get(i + 1).word()) || valueWords.contains(bare(ct.get(i + 1).word())))) weakOnValue = true;
            if (!STOCK_NEG.matcher(clause).find() && !weakOnValue) continue;   // «الخامة كريب ثقيل مش شفاف» negates the cloth, not the stock
            Map<String, String> no = valuesIn(clause, p);
            if (no.isEmpty()) continue;
            List<Tok> toks = tokens(clause, 0, clause.length());
            boolean near = false;
            Map<String, String> denied = new LinkedHashMap<>();   // the values right at the negation («XXL مش متاح»), not every value of the sentence («… متاح بس في S و M»)
            for (int i = 0; i < toks.size(); i++) {
                if (!NEG_STRICT.matcher(toks.get(i).raw()).matches() && !NEG_STRICT.matcher(toks.get(i).word()).matches()) continue;
                if (Set.of("مش", "غير", "ليس", "لا", "ومش", "فمش").contains(toks.get(i).word()) && !(i + 1 < toks.size() && (STOCK_NEG.matcher(toks.get(i).word() + " " + toks.get(i + 1).word()).find()
                        || valueWords.contains(toks.get(i + 1).word()) || valueWords.contains(bare(toks.get(i + 1).word()))))) continue;
                // «مش متوفر في XXL»: the word after the negation («متوفر») counts as part of it
                int last = i + 1 < toks.size() && AVAIL.matcher(toks.get(i + 1).word()).matches() ? i + 1 : i;
                for (int k = Math.max(0, i - 2); k <= Math.min(toks.size() - 1, last + 2); k++)
                    for (var e : no.entrySet()) {
                        List<Tok> vts = tokens(light(e.getKey()), 0, e.getKey().length());
                        final int kk = k;
                        if (!vts.isEmpty() && same(toks.get(k).word(), vts.get(0).word()) && java.util.stream.IntStream.range(0, vts.size()).allMatch(j -> kk + j < toks.size() && same(toks.get(kk + j).word(), vts.get(j).word()))) {
                            near = true; denied.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
            }
            // «… 30 قطعة بس في الأسود 256 جيجا — مفيش 32»: a no to a number of pieces, not to the option
            boolean qtyNo = false;
            for (int i = 0; i + 1 < toks.size(); i++)
                if (NEG_STRICT.matcher(toks.get(i).word()).matches() && toks.get(i + 1).word().chars().allMatch(Character::isDigit) && !valueWords.contains(toks.get(i + 1).word())   // «مفيش 42» is the size 42
                        && (i + 2 >= toks.size() || !valueWords.contains(toks.get(i + 2).word()) || Set.of("قطعه", "قطع", "حته", "pieces").contains(toks.get(i + 2).word()))) qtyNo = true;
            if (qtyNo) continue;
            // «للأسف جراب لاب توب زينترا رمادي 13 - 14 بوصة خلص حالياً»: one value of each option in the clause is the combination denied, wherever the negation sits
            boolean oneEach = new java.util.HashSet<>(no.values()).size() == no.size();
            if (!near && !oneEach) continue;
            if (!denied.isEmpty() && !oneEach) no = denied;
            Set<String> own = new java.util.HashSet<>();
            for (Variant v : p.variants()) for (String val : v.options().values()) { own.add(bare(light(val)).replace("\u200C", "")); for (Tok t : tokens(light(val), 0, val.length())) own.add(t.word()); }
            for (Tok t : tokens(light(p.name()), 0, p.name().length())) own.add(t.word());   // «ماوس باد زينترا XL»: its own name, «أحمر هادي»: its own value
            boolean foreign = false;
            for (Tok t : toks) if ((COLORS.contains(t.word()) || SIZE_CODES.contains(t.word())) && !own.contains(t.word())) foreign = true;
            if (foreign) continue;
            Map<String, String> want = new LinkedHashMap<>(no);
            for (String src : new String[]{customer == null ? "" : light(customer), sentence}) {
                Map<String, String> more = valuesIn(src, p);
                for (var e : more.entrySet()) if (!want.containsValue(e.getValue()) && more.values().stream().filter(e.getValue()::equals).count() == 1) want.put(e.getKey(), e.getValue());   // one value of that option said, so it is the one meant
            }
            if (p.variants().stream().anyMatch(v -> v.available() > 0 && v.options().values().containsAll(want.keySet())))
                return "«" + p.name() + "» IS available in " + String.join(" ", want.keySet()) + ": " + stockLine(p, want) + ". Do not say it is not; say exactly what is in stock.";
        }
        return null;
    }

    static boolean sizeOption(String name) { String n = light(name); return n.contains("مقاس") || n.contains("size") || n.contains("المقاس"); }

    static boolean colorOption(String name) { String n = light(name); return n.contains("لون") || n.contains("color") || n.contains("colour"); }

    /** The option values of {@code p} that the sentence names (value -> option name). */
    private static Map<String, String> valuesIn(String lightSentence, Product p) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, int[]> at = new java.util.HashMap<>();   // where each value was found, in tokens
        List<Tok> toks = tokens(lightSentence, 0, lightSentence.length());
        List<String> name = new ArrayList<>();
        for (Tok t : tokens(light(p.name()), 0, p.name().length())) name.add(t.word());
        List<int[]> nameAt = new ArrayList<>();   // where the product's own name stands: a value wholly inside it is not an option asked («جراب آيفون 14 سيليكون»)
        for (int i = 0; name.size() > 1 && i + name.size() <= toks.size(); i++) {
            boolean all = true;
            for (int k = 0; k < name.size() && all; k++) all = same(toks.get(i + k).word(), name.get(k)) || toks.get(i + k).word().equals(name.get(k));
            if (all) nameAt.add(new int[]{i, i + name.size()});
        }
        for (Variant v : p.variants())
            for (var e : v.options().entrySet()) {
                if (out.containsKey(e.getValue())) continue;
                List<String> w = new ArrayList<>();
                for (Tok t : tokens(light(e.getValue()), 0, e.getValue().length())) w.add(t.word());
                if (w.isEmpty()) continue;
                for (int i = 0; i + w.size() <= toks.size(); i++) {
                    boolean all = true;
                    for (int k = 0; k < w.size() && all; k++) all = same(toks.get(i + k).word(), w.get(k));
                    final int from = i, to = i + w.size();
                    // «… لآيفون 15 برو ماكس» runs past the name «اسكرينة جلاس آيفون 15»: it is the option
                    if (all && nameAt.stream().noneMatch(n -> n[0] <= from && to <= n[1])) { out.put(e.getValue(), e.getKey()); at.put(e.getValue(), new int[]{from, to}); break; }
                }
            }
        // «آيفون 15 برو ماكس» is that value, not also «آيفون 15» and «آيفون 15 برو» inside it
        Set<String> inside = new java.util.HashSet<>();
        for (var v : at.entrySet()) for (var o : at.entrySet())
            if (!o.getKey().equals(v.getKey()) && out.get(o.getKey()).equals(out.get(v.getKey())) && o.getValue()[0] <= v.getValue()[0] && o.getValue()[1] >= v.getValue()[1]
                    && o.getValue()[1] - o.getValue()[0] > v.getValue()[1] - v.getValue()[0]) inside.add(v.getKey());
        out.keySet().removeAll(inside);
        return out;
    }

    /** What is in stock, grouped by colour when the product has colours («زيتي: S، M؛ أسود: L، XL، XXL»). */
    static String stockLine(Product p, Map<String, String> focus) {
        Map<String, List<String>> by = new LinkedHashMap<>();
        for (Variant v : p.variants()) {
            if (v.available() <= 0) continue;
            String color = null;
            List<String> rest = new ArrayList<>();
            for (var e : v.options().entrySet()) if (colorOption(e.getKey()) && color == null) color = e.getValue(); else rest.add(e.getValue());
            by.computeIfAbsent(color == null ? "" : color, k -> new ArrayList<>()).add(String.join(" ", rest));
        }
        List<String> parts = new ArrayList<>();
        boolean onlyColours = by.values().stream().allMatch(vals -> vals.stream().allMatch(String::isBlank));
        if (onlyColours && !by.isEmpty()) return String.join("، ", by.keySet());   // «أسود، وردي»
        by.forEach((c, vals) -> parts.add((c.isEmpty() ? "" : c + ": ") + String.join("، ", vals.stream().filter(x -> !x.isBlank()).distinct().toList())));
        return parts.isEmpty() ? "nothing in stock" : String.join("؛ ", parts);
    }

    /** For «كل المقاسات»: the colour/size pairs that are not in stock, or null when everything is. */
    private static String missingCombos(Product p, Map<String, String> said) {
        Set<String> colors = new LinkedHashSet<>(), sizes = new LinkedHashSet<>();
        for (Variant v : p.variants()) v.options().forEach((k, val) -> { if (colorOption(k)) colors.add(val); else if (sizeOption(k)) sizes.add(val); });
        if (sizes.isEmpty()) return null;
        List<String> saidColors = said.entrySet().stream().filter(e -> colorOption(e.getValue())).map(Map.Entry::getKey).toList();
        // the colours said are the ones claimed in every size («أزرق وأسود بكل المقاسات»); none said: all of them
        List<String> missing = new ArrayList<>();
        for (String c : !saidColors.isEmpty() ? new LinkedHashSet<>(saidColors) : colors.isEmpty() ? Set.of("") : colors)
            for (String sz : sizes) {
                boolean ok = p.variants().stream().anyMatch(v -> v.available() > 0 && v.options().containsValue(sz) && (c.isEmpty() || v.options().containsValue(c)));
                if (!ok) missing.add((c.isEmpty() ? "" : c + " ") + sz);
            }
        return missing.isEmpty() ? null : "not in stock: " + String.join("، ", missing) + "; in stock: " + stockLine(p, said);
    }

    // ---- 3. "we do not have it" when we do --------------------------------------------------------------------------------

    private static final Pattern DENY = Pattern.compile("(?<!\\p{L})(?:و|ف)?(?:مفيش|مفيهوش|مفيهاش|مفيناش|مافيناش|معندناش|ماعندناش|محدش|مش\\s+عندنا|مش\\s+فيه|مش\\s+فيها|مش\\s+موجود|مش\\s+متوفر|مش\\s+متاح|غير\\s+(?:متاح|متوفر|موجود)|مش\\s+في\\s+(?:القائمه|القايمه|المنيو)|مش\\s+(?:من\\s+)?ضمن|مش\\s+بنتعامل|مش\\s+بنوفر|مبنبيعش|مش\\s+بنبيع|مش\\s+ببيع|مش\\s+لاقي|مش\\s+لاقيه|مش\\s+لقيت|مينفعش\\s+(?:ن|ت|ا)?لاقي|مش\\s+هت?لاقي|مش\\s+هن?لاقي|منلاقيش|ملقتش|مالقيتش|ملقيتش|ملاقيتش|مالقتش|مش\\s+عندي|معنديش|لا\\s+يوجد|لا\\s+نبيع|(?:بس|لكن)\\s+مش|we\\s+(?:don'?t|do\\s+not)\\s+(?:have|sell|carry)|there\\s+(?:is|are)\\s+no|we\\s+have\\s+no)(?!\\p{L})", Pattern.CASE_INSENSITIVE);
    /** Words that say nothing about which product: negations, chat words, time words, vague nouns. */
    private static final Set<String> STOP = Set.of("مش", "مفيش", "معندناش", "ماعندناش", "عندنا", "عندي", "بنبيع", "ببيع", "نبيع", "مبنبيعش", "موجود", "موجوده", "متوفر", "متوفره", "متاح", "متاحه", "يوجد",
            "حاليا", "دلوقتي", "النهارده", "النهاردة", "كامل", "كامله", "خالص", "للاسف", "بس", "لكن", "غير", "ابدا", "تاني", "نوع", "انواع", "اصناف", "صنف", "خيارات", "اختيارات", "موديلات", "موديل", "حاجه", "حاجات",
            "اكل", "منتج", "منتجات", "اي", "ايه", "كده", "كدا", "ده", "دي", "دا", "لوحده", "لوحدها", "فيه", "في", "من", "على", "علي", "عن", "مع", "او", "ولا", "و", "يا", "فندم", "حضرتك", "ان", "انه", "انها",
            "مشكله", "مشاكل", "مشكلة", "specifically", "كتالوجنا", "الكتالوج", "كتالوج", "بالنسبه", "المخزن", "مخزن", "exactly", "really",
            "بتاعنا", "بتاعتنا", "عندهم", "فيها", "فيهم", "القائمه", "قائمه", "المنيو", "منيو", "كل", "جميع", "حد", "طلب", "الطلب", "لك", "ليك", "ليكي", "كمان", "برضه", "معاك", "حق", "حقك", "معلش", "اسفين", "اسف",
            "we", "don't", "dont", "do", "not", "have", "sell", "carry", "any", "there", "is", "are", "no", "the", "a", "an", "right", "now", "currently", "at", "moment", "sorry", "unfortunately", "for", "of", "in", "but");
    /** A denial about a service, not a product (instalments, cards, branches, delivery...): out of this check. */
    private static final Pattern POLICY = Pattern.compile("(?:تقسيط|قسط|اقساط|فيزا|كارت|بطاقه|كاش|استبدال|استرجاع|ترجيع|مرتجع|ضمان|كود|كوبون|خصم|عرض|عروض|توصيل|شحن|دليفري|فرع|فروع|مواعيد|مفتوح|مقفول|رقم|عنوان|مقاس|مقاسات|لون|الوان|installments?|card|visa|return|exchange|warranty|coupon|code|discount|delivery|shipping|branch|size|colou?r)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> AUDIENCE = Set.of("رجالي", "رجال", "حريمي", "نسائي", "ستات", "ولادي", "ولاد", "اولاد", "بناتي", "بنات", "اطفال", "بيبي", "men", "women", "kids", "boys", "girls", "baby");

    private static void falseDenial(String answer, Shop shop, List<Problem> out) {
        String l = light(answer);
        for (int[] s : sentences(answer)) {
            String whole = l.substring(s[0], s[1]);
            // the clause that denies («الجينز غير متاح عندنا حالياً»), not the alternative offered after it («وأقرب حاجة بنطلون كحلي»)
            String sentence = null;
            Matcher d = null;
            for (String clause : whole.split("[،,؛;]")) { Matcher m = DENY.matcher(clause); if (m.find()) { sentence = clause; d = m; break; } }
            if (sentence == null || POLICY.matcher(sentence).find()) continue;
            if (!valuesInAny(sentence)) {
                // «ومفيش أي خدش عليه»: what is denied is a flaw or a thing already said, not a product
                var next = tokens(sentence, d.end(), sentence.length()).stream().map(Tok::word).filter(w -> !STOP.contains(w) && w.length() >= 3).findFirst();
                if (next.isPresent() && NOT_A_THING.contains(next.get())) continue;
                List<String> after = content(sentence.substring(d.end()), 4, true);
                if (optionValueAt(sentence.substring(d.end()), shop)) continue;   // «مفيهاش فري سايز», «مفيش منه XXL»: an option of the product denied, not a product
                List<String> beforeKinds = content(sentence.substring(0, d.start()), 6, false).stream().filter(w -> !AUDIENCE.contains(w)).toList();
                boolean butNot = d.group().startsWith("بس") || d.group().startsWith("لكن");
                List<List<String>> queries = new ArrayList<>();
                if (butNot && !after.isEmpty()) for (String k : beforeKinds) queries.add(List.of(k, after.get(0)));   // «عندنا أحذية للولاد بس مش رجالي» -> أحذية رجالي
                else if (!after.isEmpty()) {
                    queries.add(after);
                    boolean model = after.stream().anyMatch(w -> w.chars().allMatch(Character::isDigit));   // «معنديش اسكرينة لـ 14 برو»: about that model, not every screen
                    if (after.size() > 1 && !model) for (String w : after) if (!AUDIENCE.contains(w)) queries.add(List.of(w));
                }
                // «الثومية ... مش ببيعها لوحدها», «بالنسبة لـ X12، مفيش في كتالوجنا»: the product named before the denial
                if (!butNot) for (String k : beforeKinds) queries.add(List.of(k));
                for (List<String> q : queries) {
                    if (q.isEmpty() || q.stream().anyMatch(w -> w.length() < 3 && !w.chars().allMatch(Character::isDigit)) || q.stream().allMatch(w -> w.chars().allMatch(Character::isDigit))) continue;
                    List<String> stems = q.stream().map(FactCheck::stem).toList();
                    List<Named> hits = new ArrayList<>(shop.searchAll(stems));
                    if (hits.isEmpty()) continue;
                    Set<String> offered = new java.util.HashSet<>();
                    for (Mention om : mentions(answer, shop.names())) offered.add(om.slug());
                    if (hits.stream().anyMatch(h -> offered.contains(h.slug()))) continue;   // «مش موجود للرضع، لكن عندنا طقم بيبي…»: the reply offers them itself
                    hits.sort(java.util.Comparator.comparing((Named h) -> !stems.stream().allMatch(w -> light(h.name()).contains(w))));   // products named for it first
                    List<String> shown = new ArrayList<>();
                    for (Named h : hits.stream().limit(5).toList()) {
                        Product p = shop.product(h.slug());
                        shown.add(h.name() + (p != null && !p.variants().isEmpty() ? " (" + num(p.variants().stream().mapToDouble(Variant::price).min().orElse(0), false) + (arabic(answer) ? " جنيه)" : " EGP)") : ""));
                    }
                    // still denied after the rewrite: the wrong sentence goes (a list pasted in its place read badly), the rest of the reply stays
                    String without = (answer.substring(0, s[0]) + answer.substring(s[1])).replaceAll("\\n{3,}", "\n\n").trim();
                    out.add(new Problem("FALSE_DENIAL", "You said we do not have «" + String.join(" ", q) + "», but the shop HAS: " + String.join("; ", shown) + ". Never deny it; offer these with their prices.",
                            without.length() >= 25 ? without : null));
                    return;
                }
            }
        }
    }

    /** The text right after a no starts with an option value of some product of the shop («فري سايز», «١٫٥ لتر»), maybe after «منه / فيه / بـ / لون / مقاس». */
    private static boolean optionValueAt(String afterDeny, Shop shop) {
        List<Tok> t = tokens(afterDeny, 0, afterDeny.length());
        int i = 0;
        while (i < t.size() && Set.of("منه", "منها", "فيه", "فيها", "عندنا", "عندي", "لون", "اللون", "مقاس", "المقاس", "حجم", "الحجم", "خيار", "اي", "حاجه").contains(t.get(i).word())) i++;
        if (i >= t.size()) return false;
        for (Named n : shop.names()) {
            Product p = shop.product(n.slug());
            if (p == null) continue;
            for (Variant v : p.variants()) for (String val : v.options().values()) {
                List<Tok> vt = tokens(light(val), 0, val.length());
                if (vt.isEmpty() || i + vt.size() > t.size()) continue;
                boolean all = true;
                for (int k = 0; k < vt.size() && all; k++) all = same(t.get(i + k).word(), vt.get(k).word()) || bare(t.get(i + k).word()).equals(vt.get(k).word());
                if (all) return true;
            }
        }
        return false;
    }

    /** «عذراً، …», «للأسف …», "sorry": the sentence opens with an apology. */
    private static final Pattern APOLOGY = Pattern.compile("^[^\\p{L}]*(?:عذرا|للاسف|معلش|اسف|اسفين|sorry|unfortunately)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    /** The word right after «مفيش» when what is denied is a limit, an objection or an exception, not a product. */
    private static final Set<String> NOT_STOCK = Set.of("حد", "مانع", "داعي", "فرق", "غير", "الا", "سوي", "مشكله", "مشاكل", "مشكلة", "قلق", "خوف", "تعب", "زحمه");

    /** «بنبيع أكل شاورما ومشويات مش مياه معدنية»: what we sell, and then what we do not. */
    private static final Pattern SELLS_NOT = Pattern.compile("(?<!\\p{L})(?:بنبيع|نبيع|بنقدم|نقدم|متخصصين\\s+في)\\s+[^.؟?!\\n]{2,60}?\\s(?:و)?(?:مش|وليس|ليس)(?!\\p{L})");

    /** «عندنا شاورما وبروستد فقط»: what we have is only that. */
    private static final Pattern ONLY_CLAIM = Pattern.compile("(?:عندنا|بنبيع|متخصصين\\s+في)\\s+[^.؟?!\\n]{2,60}?\\s(?:فقط|بس)(?=\\s*(?:[.!؟?،,\\n]|$))");

    /**
     * The customer named a product of the shop that is in stock («عايز ويب كام زينترا 1080p أسود») and the reply says we do not have it («مفيش ويب كام زينترا عندنا», «مش بنبيع مياه
     * معدنية», «عندنا شاورما وبروستد فقط»). A denial of an option, a service or something else is the other checks' business. Code writes the truth when the rewrite still denies it.
     */
    private static void namedDenied(String customer, String answer, List<Mention> ms, List<String> touched, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        if (customer == null || out.stream().anyMatch(x -> x.kind().equals("FALSE_DENIAL") || x.kind().equals("OPTION_STOCK"))) return;
        var asked = mentions(customer, shop.names()).stream().map(Mention::slug).distinct().toList();
        // «انا كريم، القاهرة…» names nothing: the product of the chat, denied in a sentence that names it
        boolean fromChat = asked.isEmpty() && touched != null && touched.size() == 1;
        if (fromChat) asked = List.of(touched.get(0));
        if (asked.size() != 1) return;
        Product p = load.apply(asked.get(0));
        if (p.variants().stream().noneMatch(v -> v.available() > 0)) return;
        String lc = light(customer), la = light(answer);
        Map<String, String> picked = valuesIn(lc, p);
        List<Variant> fit = p.variants().stream().filter(v -> v.options().values().containsAll(picked.keySet())).toList();
        if (fit.stream().noneMatch(v -> v.available() > 0)) return;   // the option chosen is sold out: saying so is right
        if (foreignValue(lc, p, shop, load) != null) return;   // asked in an option it does not have
        boolean namesNone = ms.isEmpty();
        String ownNumbers = "(?<![\\d.])(?:" + String.join("|", java.util.Arrays.stream(light(p.name()).split("[^\\p{L}\\d]+")).filter(w -> w.matches(".*\\d.*")).map(Pattern::quote).toList()) + "|$^)(?![\\d.])";   // «1080p» in «ويب كام زينترا 1080p»
        // «البوت … متوفر، سعره ١٧٥٠»: a sentence that offers it says we have it, whatever another clause denies
        for (int[] s : sentences(answer)) {
            String se = la.substring(s[0], s[1]);
            if (ms.stream().anyMatch(m -> m.start() >= s[0] && m.start() < s[1] && m.slug().equals(p.slug())) && SAYS_STOCK.matcher(se).find() && !NEG_STRICT.matcher(se).find() && !DENY.matcher(se).find() && !APOLOGY.matcher(se).find()) return;
        }
        String pn = light(p.name());
        for (int[] s : sentences(answer)) {
            String whole = la.substring(s[0], s[1]);
            // «زينترا بوك 14 مفيهوش نسخة أوبن بوكس» for «زينترا بوك 14 أوبن بوكس»: a product whose name is inside the one asked, with the rest of the asked name in the sentence, is the one asked
            java.util.function.Predicate<Mention> itsOwn = m -> m.slug().equals(p.slug()) || inner(m.slug(), pn, whole, shop);
            boolean aboutIt = ms.stream().anyMatch(m -> m.start() >= s[0] && m.start() < s[1] && itsOwn.test(m));
            boolean other = ms.stream().anyMatch(m -> m.start() >= s[0] && m.start() < s[1] && !itsOwn.test(m));
            String denied = null;
            for (String clause : whole.split("[،,؛;]")) {
                Matcher d = DENY.matcher(clause);
                boolean deny = d.find();
                if (!deny) { d = SELLS_NOT.matcher(clause); deny = d.find(); }   // «بنبيع أكل شاورما ومشويات مش مياه معدنية»
                if (deny) {
                    var next = tokens(clause, d.end(), clause.length()).stream().map(Tok::word).filter(w -> !STOP.contains(w) && w.length() >= 3).findFirst();
                    if (next.isPresent() && (NOT_A_THING.contains(next.get()) || next.get().startsWith("مشكل"))) continue;   // «مفيش مشكلة», «مفيش خدش عليه»
                    var right = tokens(clause, d.end(), clause.length()).stream().map(Tok::word).findFirst();
                    if (right.isPresent() && NOT_STOCK.contains(right.get())) continue;   // «مفيش حد أقصى للكمية», «مفيش مانع», «مفيش غير الأبيض»
                    // «٨ قطع بس، مفيش ١٨», «مش ١٣»: a quantity or a price, not the product
                    if (clause.replaceAll(ownNumbers, " ").matches("(?s).*\\d.*") || POLICY.matcher(clause).find() || valuesInAny(clause) || !valuesIn(clause, p).isEmpty()) continue;
                    denied = clause;
                    break;
                }
                // «عندنا ٣ قطع بس», «عندنا الأسود بس», «عندنا توصيل للقاهرة بس» are counts, options and services, not what the shop sells
                if (ONLY_CLAIM.matcher(clause).find() && !aboutIt && !clause.matches("(?s).*\\d.*") && !POLICY.matcher(clause).find() && !valuesInAny(clause) && valuesIn(clause, p).isEmpty()) { denied = clause; break; }
            }
            if (denied == null || other && !aboutIt || !aboutIt && (fromChat || !namesNone && !ONLY_CLAIM.matcher(denied).find())) continue;
            boolean ar = arabic(answer) || arabic(customer);
            java.util.TreeSet<Double> prices = new java.util.TreeSet<>();
            for (Variant v : fit) if (v.available() > 0) prices.add(v.price());
            String price = prices.size() == 1 ? (ar ? " بـ " + num(prices.first(), false) + " جنيه" : " at " + num(prices.first(), false) + " EGP") : "";
            String opts = picked.isEmpty() && p.variants().size() > 1 ? (ar ? "، المتاح منه: " + stockLine(p, Map.of()) : "; in stock: " + stockLine(p, Map.of())) : "";
            String what = "«" + p.name() + "»" + (picked.isEmpty() ? "" : " " + String.join(" ", picked.keySet()));
            String truth = ar ? "أيوه عندنا " + what + " متاح ✅" + price + (price.isEmpty() && opts.isEmpty() ? " (" + priceLine(p, true) + ")" : "") + opts + ". تحب أجهزهولك؟"
                             : "Yes, we have " + what + " in stock ✅" + price + opts + ". Shall I get it ready for you?";
            out.add(new Problem("FALSE_DENIAL", "The customer asked for " + what + " and the shop HAS it in stock" + price + ". Never say we do not have it: offer it with its price" + (opts.isEmpty() ? "" : " and options") + ".", truth));
            return;
        }
    }

    /** The product's name is inside the asked name ({@code askedLight}) and the words the asked name has beyond it are in the sentence. */
    private static boolean inner(String slug, String askedLight, String lightSentence, Shop shop) {
        Named n = shop.names().stream().filter(x -> x.slug().equals(slug)).findFirst().orElse(null);
        if (n == null) return false;
        String nl = light(n.name());
        if (nl.equals(askedLight) || !askedLight.startsWith(nl + " ")) return false;
        String rest = askedLight.substring(nl.length()).trim();
        List<Tok> st = tokens(lightSentence, 0, lightSentence.length());
        for (Tok t : tokens(rest, 0, rest.length())) if (st.stream().noneMatch(x -> same(x.word(), t.word()))) return false;
        return true;
    }

    private static boolean valuesInAny(String lightSentence) {
        for (Tok t : tokens(lightSentence, 0, lightSentence.length())) if (SIZE_CODES.contains(t.word()) || COLORS.contains(t.word())) return true;
        return false;
    }

    private static final Set<String> COLORS = Set.of("اسود", "ابيض", "رمادي", "بيج", "بني", "احمر", "وردي", "برتقالي", "اصفر", "اخضر", "زيتي", "تركواز", "ازرق", "كحلي", "سماوي", "بنفسجي", "ذهبي", "فضي",
            "black", "white", "grey", "gray", "beige", "brown", "red", "pink", "orange", "yellow", "green", "olive", "blue", "navy", "purple", "gold", "silver");

    /** Up to {@code max} words that could name a product (no stop words, no numbers). */
    /** Words that point at something already said («مفيش خدش عليه») and never name a product. */
    private static final Set<String> NOT_A_THING = Set.of("عليه", "عليها", "عليهم", "فيه", "فيها", "فيهم", "منه", "منها", "منهم", "ليه", "ليها", "ليهم", "بيه", "بيها", "معاه", "معاها",
            "خدش", "خدوش", "عيب", "عيوب", "مشكله", "مشاكل");

    private static List<String> content(String lightPart, int max) { return content(lightPart, max, false); }

    /** The content words of a part of a sentence; with {@code numbers}, a model number («لـ 14 برو») stays, so a denial of a model the shop lacks is not read as a denial of the kind. */
    private static List<String> content(String lightPart, int max, boolean numbers) {
        List<String> out = new ArrayList<>();
        for (Tok t : tokens(lightPart, 0, lightPart.length())) {
            String w = t.word();
            boolean digits = w.chars().allMatch(Character::isDigit);
            if (numbers && digits && w.length() >= 2) { out.add(w); if (out.size() >= max) break; continue; }
            if (w.length() < 3 || STOP.contains(w) || NOT_A_THING.contains(w) || digits) continue;
            out.add(w);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ---- 4. where the shop delivers ---------------------------------------------------------------------------------------

    /** Governorates and the cities and areas customers name instead, as bare words, with their governorate code. */
    static final Map<String, String> AREAS = areas();

    private static Map<String, String> areas() {
        Map<String, String> m = new LinkedHashMap<>();
        String[] kv = {"قاهره", "CAI", "cairo", "CAI", "مدينه نصر", "CAI", "تجمع", "CAI", "معادي", "CAI", "زمالك", "CAI", "مصر الجديده", "CAI", "مقطم", "CAI", "حلوان", "CAI", "شبرا", "CAI", "عباسيه", "CAI", "التحرير", "CAI",
                "جيزه", "GIZ", "giza", "GIZ", "اكتوبر", "GIZ", "شيخ زايد", "GIZ", "زايد", "GIZ", "هرم", "GIZ", "فيصل", "GIZ", "دقي", "GIZ", "مهندسين", "GIZ", "حدائق الاهرام", "GIZ",
                "اسكندريه", "ALX", "اسكندريه", "ALX", "alexandria", "ALX", "alex", "ALX", "قليوبيه", "QLY", "بنها", "QLY", "شبرا الخيمه", "QLY", "شرقيه", "SHR", "زقازيق", "SHR", "العاشر", "SHR",
                "دقهليه", "DKH", "منصوره", "DKH", "mansoura", "DKH", "غربيه", "GHR", "طنطا", "GHR", "محله", "GHR", "منوفيه", "MNF", "شبين الكوم", "MNF", "بحيره", "BHR", "دمنهور", "BHR",
                "كفر الشيخ", "KFS", "دمياط", "DMT", "بورسعيد", "PSD", "بور سعيد", "PSD", "اسماعيليه", "ISM", "سويس", "SUZ", "فيوم", "FYM", "بني سويف", "BNS", "منيا", "MNY", "اسيوط", "AST", "سوهاج", "SHG",
                "قنا", "QNA", "اقصر", "LXR", "luxor", "LXR", "اسوان", "ASN", "aswan", "ASN", "بحر الاحمر", "RSS", "غردقه", "RSS", "hurghada", "RSS", "وادي الجديد", "WAD", "مطروح", "MTR", "مرسي مطروح", "MTR",
                "علمين", "MTR", "ساحل", "MTR", "شمال سيناء", "NSN", "عريش", "NSN", "جنوب سيناء", "SSN", "شرم", "SSN", "شرم الشيخ", "SSN", "دهب", "SSN", "sharm", "SSN"};
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final Pattern DELIVERY = Pattern.compile("(?:بنوصل|نوصل|بيوصل|هيوصل|يوصل|توصيل|التوصيل|شحن|الشحن|بنشحن|نشحن|deliver|delivery|ship|shipping)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ONLY_WORDS = Pattern.compile("(?:(?<!\\p{L})بس(?!\\p{L})|فقط|داخل|only|just)", Pattern.CASE_INSENSITIVE);

    private static void area(String answer, Shop shop, List<Problem> out) {
        Set<String> served = shop.served();
        if (served.isEmpty()) return;
        String l = light(answer);
        boolean ar = arabic(answer);
        for (int[] s : sentences(answer)) {
            String sentence = l.substring(s[0], s[1]);
            if (!DELIVERY.matcher(sentence).find()) continue;
            List<Tok> toks = tokens(sentence, 0, sentence.length());
            List<String[]> found = new ArrayList<>();   // {code, the words said, index of the first word}
            for (var e : AREAS.entrySet()) {
                List<String> w = new ArrayList<>();
                for (Tok t : tokens(e.getKey(), 0, e.getKey().length())) w.add(t.word());
                for (int i = 0; i + w.size() <= toks.size(); i++) {
                    boolean all = true;
                    for (int k = 0; k < w.size() && all; k++) all = toks.get(i + k).word().equals(w.get(k));
                    if (all && found.stream().noneMatch(f -> f[0].equals(e.getValue()))) { found.add(new String[]{e.getValue(), e.getKey(), String.valueOf(i)}); break; }
                }
            }
            if (found.isEmpty()) continue;
            boolean neg = NEG.matcher(sentence).find() || sentence.matches("(?s).*(?<!\\p{L})(?:لا|لأ|no)(?!\\p{L}).*");
            String names = String.join(ar ? " و" : " and ", served.stream().map(c -> govName(c, ar)).toList());
            for (String[] f : found) {
                if (!served.contains(f[0]) && !neg) {
                    String pick = shop.pickup();
                    String fixed = ar ? "للأسف التوصيل عندنا لـ" + names + " بس" + (pick != null ? "، وممكن تستلم من " + (light(pick).startsWith("فرع") ? "" : "فرع ") + pick : "") + "."
                                      : "Sorry, we only deliver to " + names + (pick != null ? "; you can also pick up from our branch (" + pick + ")" : "") + ".";
                    out.add(new Problem("AREA", "We do NOT deliver to " + f[1] + ": delivery is only to " + names + ". Never promise delivery or a fee there.", replaceSentence(answer, s[0], s[1], fixed)));
                    return;
                }
                // «مش بنوصل للمعادي» when the shop serves Cairo: a negation right before the area, and no «بس / داخل» (which lists where we DO deliver)
                int at = Integer.parseInt(f[2]);
                boolean negRight = false;
                for (int k = Math.max(0, at - 4); k < at; k++) if (NEG.matcher(toks.get(k).word()).matches() || toks.get(k).word().startsWith("مبن") || toks.get(k).word().equals("لا")) negRight = true;
                if (served.contains(f[0]) && negRight && !ONLY_WORDS.matcher(sentence).find()) {
                    String zone = shop.zoneLine(f[0], ar);
                    out.add(new Problem("AREA", "We DO deliver to " + f[1] + (zone != null ? " (" + zone + ")" : "") + ". Do not say we do not.", zone == null ? null : replaceSentence(answer, s[0], s[1], zone)));
                    return;
                }
            }
        }
    }

    static String govName(String code, boolean arabic) {
        String n = ChatService.GOVERNORATES.getOrDefault(code, code);
        int i = n.indexOf(' ');
        if (arabic) {
            // the Arabic part ends where the English name starts
            int k = 0;
            while (k < n.length() && (n.charAt(k) > 0x7F || n.charAt(k) == ' ')) k++;
            return n.substring(0, k).trim();
        }
        return i < 0 ? n : n.replaceAll("^[^A-Za-z]+", "").trim();
    }

    // ---- 5. opening hours ------------------------------------------------------------------------------------------------

    private static final Pattern OPEN_WORDS = Pattern.compile("(?:نفتح|بنفتح|يفتح|بيفتح|هنفتح|فاتحين|مفتوح|مفتوحين|نقفل|بنقفل|بيقفل|يقفل|قافلين|مواعيد|ساعات\\s+العمل|ساعه\\s+الشغل|ساعات\\s+الشغل|شغالين|الفتح|open|opens|opening|close|closes|closing|hours)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("(?<![\\d:])(\\d{1,2})(?::(\\d{2}))?\\s*(?:ال)?(?:ساعه\\s*)?(الضهر|الظهر|ظهرا|ضهرا|ظهر|ضهر|الصبح|صباحا|صباح|بالليل|بليل|الليل|مساء|مساءا|بالمساء|فجرا|الفجر|بعد\\s+نص\\s+الليل|am|pm|a\\.m|p\\.m|ص(?!\\p{L})|م(?!\\p{L}))", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIDNIGHT = Pattern.compile("(?:نص\\s+الليل|منتصف\\s+الليل|midnight)", Pattern.CASE_INSENSITIVE);

    private static void hours(String answer, Shop shop, List<Problem> out) {
        List<int[]> week = shop.hours();
        if (week.isEmpty()) return;
        Set<Integer> real = new java.util.HashSet<>();
        for (int[] d : week) { real.add(d[0] / 60 % 24); real.add(d[1] / 60 % 24); }
        String l = light(answer);
        for (int[] s : sentences(answer)) {
            String sentence = l.substring(s[0], s[1]);
            if (!OPEN_WORDS.matcher(sentence).find() || sentence.contains("دقيق") || sentence.contains("minute")) continue;
            List<Set<Integer>> said = new ArrayList<>();
            Matcher m = TIME.matcher(sentence);
            while (m.find()) said.add(candidates(Integer.parseInt(m.group(1)), m.group(3).toLowerCase(Locale.ROOT)));
            Matcher mid = MIDNIGHT.matcher(sentence);
            while (mid.find()) if (!sentence.substring(Math.max(0, mid.start() - 5), mid.start()).contains("بعد")) said.add(Set.of(0));
            for (Set<Integer> c : said) {
                if (c.stream().anyMatch(real::contains)) continue;
                String right = hoursText(week, arabic(answer));
                boolean same = week.stream().allMatch(d -> d[0] == week.get(0)[0] && d[1] == week.get(0)[1]);
                out.add(new Problem("HOURS", "The shop's opening hours are " + hoursText(week, false) + ". Say only these.", same ? replaceSentence(answer, s[0], s[1], right) : null));
                return;
            }
        }
    }

    private static Set<Integer> candidates(int h, String when) {
        if (h > 23) return Set.of(-1);
        if (when.startsWith("ال") && (when.contains("ضهر") || when.contains("ظهر")) || when.contains("ظهر") || when.contains("ضهر")) return Set.of(h == 12 ? 12 : (h + 12) % 24);
        if (when.contains("صبح") || when.contains("صباح") || when.startsWith("a") || when.equals("ص") || when.contains("فجر") || when.contains("بعد")) return Set.of(h % 12);
        if (when.startsWith("p") || when.equals("م")) return Set.of(h == 12 ? 12 : (h + 12) % 24);
        if (when.contains("ليل") || when.contains("مساء")) return h == 12 ? Set.of(0) : h <= 5 ? Set.of(h, h + 12) : Set.of(h + 12 > 23 ? h : h + 12);
        return Set.of(h % 24, (h + 12) % 24);
    }

    /** «من ١٢ الضهر لحد ٢ بالليل» / "from 12 pm to 2 am", for shops open the same hours every day (otherwise the days in short). */
    static String hoursText(List<int[]> week, boolean arabic) {
        int[] d = week.get(0);
        return arabic ? "مواعيدنا كل يوم من " + clock(d[0], true) + " لحد " + clock(d[1], true) + "." : "We are open every day from " + clock(d[0], false) + " to " + clock(d[1], false) + ".";
    }

    private static String clock(int minutes, boolean arabic) {
        int h = minutes / 60 % 24, mm = minutes % 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        String t = h12 + (mm > 0 ? ":" + (mm < 10 ? "0" + mm : mm) : "");
        if (!arabic) return t + (h < 12 ? " am" : " pm");
        String part = h == 0 ? "بالليل" : h < 5 ? "بالليل" : h < 12 ? "الصبح" : h < 16 ? "الضهر" : h < 19 ? "العصر" : "بالليل";
        return t + " " + part;
    }

    // ---- 6. "on sale" for a product that is not --------------------------------------------------------------------------

    private static final Pattern SALE_WORDS = Pattern.compile("(?:خصم|خصومات|تخفيض|تخفيضات|مخفض|مخفضه|عليه\\s+عرض|عليها\\s+عرض|عليهم\\s+عرض|بدل\\s+\\d|بدلا\\s+من\\s+\\d|قبل\\s+الخصم|(?<![a-z])sale(?![a-z])|discount|% off|(?<![a-z])offers?(?![a-z]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_SALE = Pattern.compile("(?:بدون|من\\s+غير|مش\\s+عليه|مش\\s+عليها|مفيش|no|without|not)\\s+(?:اي\\s+)?(?:خصم|تخفيض|عرض|discount|sale)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-•*▪✅🔸🔹]|\\d+\\s*[-.)]|[٠-٩]+\\s*[-.)])");

    /**
     * A product shown as discounted that has no old price: the discount words sit in that product's own part of the sentence («جينز سكيني بـ ٧٨٠ (مخفض من ٩٢٠)، أو جاكيت جينز بـ ١٢٥٠»
     * only puts the jeans on sale), or the customer asked for offers and the reply lists products under a heading that says they are discounted.
     */
    private static void sale(String customer, String answer, List<Mention> ms, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        boolean asked = customer != null && SALE_WORDS.matcher(light(customer)).find();
        List<int[]> ss = sentences(answer);
        String first = ss.isEmpty() ? "" : l.substring(ss.get(0)[0], ss.get(0)[1]);
        boolean saleList = asked && SALE_WORDS.matcher(first).find() && !NO_SALE.matcher(first).find() && !first.contains("كود") && !first.contains("code") && !first.contains("كوبون");
        for (int[] s : ss) {
            List<Mention> here = ms.stream().filter(m -> m.start() >= s[0] && m.start() < s[1]).toList();
            if (here.isEmpty()) continue;
            int lineStart = s[0] == 0 ? 0 : answer.lastIndexOf('\n', s[0] - 1) + 1;
            int lineEnd = answer.indexOf('\n', s[0]);
            if (lineEnd < 0) lineEnd = answer.length();
            boolean listLine = LIST_LINE.matcher(answer.substring(lineStart, lineEnd)).find();
            for (int i = 0; i < here.size(); i++) {
                Mention m = here.get(i);
                String part = l.substring(m.start(), i + 1 < here.size() ? here.get(i + 1).start() : s[1]);
                boolean claimed = (SALE_WORDS.matcher(part).find() && !NO_SALE.matcher(part).find()) || (saleList && listLine && here.size() == 1 && !NO_SALE.matcher(part).find());
                if (!claimed || part.contains("كود") || part.contains("code") || part.contains("كوبون")) continue;
                Product p = load.apply(m.slug());
                if (p.variants().isEmpty() || p.onSale()) continue;
                String line = l.substring(lineStart, lineEnd);
                if (shop.names().stream().anyMatch(n -> !n.slug().equals(p.slug()) && load.apply(n.slug()).onSale() && allWordsOf(line, n.slug(), shop))) continue;   // the discounted bundle named in the line
                String fixed = listLine && here.size() == 1 ? answer.substring(0, lineStart) + answer.substring(Math.min(answer.length(), lineEnd + 1)) : null;
                if (fixed != null) fixed = fixed.replaceAll("\\n{3,}", "\n\n").trim();
                out.add(new Problem("SALE", "«" + p.name() + "» is NOT discounted (no old price): never present it as an offer or with a discount.", fixed == null || fixed.isBlank() ? null : fixed));
                return;
            }
        }
    }

    // ---- 7. a reply that ignores the question ----------------------------------------------------------------------------

    private static final Pattern GENERIC = Pattern.compile("(?:(?:ازاي|ازاى|ازي)\\s+(?:اقدر|اقدار)\\s+(?:اساعدك|اخدمك)|اقدر\\s+(?:اساعدك|اخدمك)\\s+(?:ازاي|بايه|في\\s+ايه)|(?:محتاج|محتاجه|عايز|عايزه)\\s+(?:تطلب\\s+)?ايه\\s*(?:النهارده)?\\s*[؟?]?\\s*$|how\\s+can\\s+i\\s+help|what\\s+can\\s+i\\s+do\\s+for\\s+you)", Pattern.CASE_INSENSITIVE);

    private static final Pattern WANT = Pattern.compile("(?:عايز|عايزه|عاوز|عاوزه|محتاج|محتاجه|اطلب|هطلب|ابغي|ابغى|نفسي|want|need|order|looking\\s+for)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SMALL_TALK = Set.of("شكرا", "جدا", "متشكر", "متشكره", "تسلم", "تسلمي", "تمام", "اوك", "ميرسي", "اهلا", "السلام", "عليكم", "صباح", "مساء", "الخير", "النور", "سلام",
            "thanks", "thank", "you", "hello", "hi", "hey", "good", "morning", "evening", "okay", "bye");

    private static void offTopic(String customer, String answer, List<Mention> ms, Shop shop, List<Problem> out) {
        if (customer == null || answer.length() > 160 || !ms.isEmpty()) return;
        String la = light(answer);
        if (!GENERIC.matcher(la).find() || la.chars().anyMatch(Character::isDigit)) return;
        String lc = light(customer);
        List<String> words = content(lc, 6).stream().filter(w -> !SMALL_TALK.contains(w) && !WANT.matcher(w).matches()).toList();
        boolean asked = !mentions(customer, shop.names()).isEmpty() || (WANT.matcher(lc).find() && !words.isEmpty()) || words.size() >= 3;
        if (!asked) return;
        out.add(new Problem("OFF_TOPIC", "Your reply ignores what the customer asked («" + customer.trim() + "»): answer exactly that (search_products for the product, then give its price and options, or go on with the order).", null));
    }

    // ---- 8. products of another kind than the one asked for ---------------------------------------------------------------

    /**
     * «عندكم أكل نباتي؟» answered with desserts only: the customer's word is a kind the shop's products carry (a tag, a category, a word of their names: «نباتي», «للأطفال», «شتوي»),
     * the reply names products, and none of them is of that kind. Null-safe; one problem at most.
     */
    private static void wrongKind(String customer, String answer, List<Mention> ms, Shop shop, List<Problem> out) {
        if (customer == null || ms.isEmpty() || !mentions(customer, shop.names()).isEmpty()) return;   // a product asked for by name is the other checks' business
        Set<String> tags = shop.tags();
        if (tags.isEmpty()) return;
        Set<String> named = new java.util.HashSet<>();
        for (Mention m : ms) named.add(m.slug());
        for (String w : content(light(customer), 4)) {
            if (w.length() < 4 || AUDIENCE.contains(w) || POLICY.matcher(w).find()) continue;
            String st = stem(w);
            if (tags.stream().noneMatch(t -> t.equals(w) || stem(t).equals(st))) continue;   // only a kind the shop itself tags its products with
            if (tokens(light(answer), 0, answer.length()).stream().anyMatch(t -> stem(t.word()).equals(st))) return;   // «عندنا جراب مخصوص لنوفا X12 برو»: the reply is about that kind
            List<Named> kind = shop.searchAll(List.of(stem(w)));
            if (kind.size() < 2) continue;
            if (kind.stream().anyMatch(k -> named.contains(k.slug()))) return;   // it named at least one of them: fine
            List<String> shown = kind.stream().limit(5).map(Named::name).toList();
            out.add(new Problem("WRONG_KIND", "The customer asked for «" + w + "»; the products you named are not that. The shop's «" + w + "» products: " + String.join("، ", shown) + ". Answer with these.", null));
            return;
        }
    }

    // ---- 9. a size word the product does not have ------------------------------------------------------------------------

    /** The everyday size words of food and goods («دبل» for a sandwich that comes in عادي / كبير / سوبر). */
    private static final Set<String> SIZE_WORDS = Set.of("دبل", "سنجل", "وسط", "صغير", "عائلي", "كبير", "سوبر", "عادي", "جامبو", "ميني");

    private static void inventedValue(String answer, List<Mention> ms, String only, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        String slug = ms.size() == 1 ? ms.get(0).slug() : ms.isEmpty() ? only : null;
        if (slug == null) return;
        Product p = load.apply(slug);
        Set<String> values = new java.util.HashSet<>();
        for (Variant v : p.variants()) for (String val : v.options().values()) for (Tok t : tokens(light(val), 0, val.length())) values.add(t.word());
        if (values.stream().noneMatch(SIZE_WORDS::contains)) return;   // only products sold by such sizes
        String l = light(answer);
        int from = ms.isEmpty() ? 0 : ms.get(0).end();
        List<Tok> after = tokens(l, from, l.length());
        for (int i = 0; i < Math.min(after.size(), 4); i++) {   // right after the name: «ساندوتش شاورما فراخ دبل»
            String w = after.get(i).word();
            String next = i + 1 < after.size() ? after.get(i + 1).word() : "", prev = i > 0 ? after.get(i - 1).word() : "";
            if (Set.of("جدا", "اوي", "قوي", "خالص", "شويه").contains(next) || Set.of("طلب", "كميه", "عدد", "رقم", "مبلغ").contains(prev)) continue;   // «ده طلب كبير جدًا»: not a size
            final String before = prev;
            String ownKind = light(p.name()).split("\\s+")[0];
            if (!before.isEmpty() && !same(before, ownKind) && shop.names().stream().anyMatch(n -> !n.words().isEmpty() && (same(before, n.words().get(0)) || bare(before).equals(n.words().get(0))))) continue;   // «وبطاطس كبير»: the fries' size
            if (SIZE_WORDS.contains(w) && !values.contains(w)) {
                String upTo = l.substring(from, after.get(i).start());
                if (NEG_STRICT.matcher(upTo).find() || DENY.matcher(upTo).find()) return;   // «وجبة … مش بتيجي كبير»: said no to it
                List<String> real = values.stream().filter(SIZE_WORDS::contains).sorted().toList();
                out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» does not come in «" + w + "»; its sizes are " + String.join("، ", real) + ". Never choose an option for the customer: ask them.", null));
                return;
            }
        }
    }

    // ---- 10. more pieces than the stock -------------------------------------------------------------------------------------

    private static final Pattern ASKED_QTY = Pattern.compile("(?<![\\p{L}\\d.])(\\d{1,4})\\s+(?!جنيه|ج(?!\\p{L})|egp|le(?![a-z])|%|جيجا|gb|tb|بوصه|سم|متر|مم|سنه|سنين|شهر|يوم|ايام|وات|w(?![a-z])|مللي|الف)\\p{L}");

    /**
     * «عايز ١٠ موبايلات نوفا ميني 8» answered «متوفر بالكمية»: the customer asked for more pieces than the product has in stock (all its options together) and the reply offers them.
     * Works for any product: the number comes before a word that is not a unit or a price.
     */
    private static final Pattern ADDRESS_BEFORE = Pattern.compile("(?:شارع|ش|عماره|عمارة|شقه|دور|الدور|بلوك|برج|مبني|رقم|منطقه|مربع|مجاوره|الحي|حي|street|st|apt|floor|building|block)\\.?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Asking for the order details offers the item as it was asked («محتاج منك: الاسم، رقم الموبايل، العنوان»). */
    private static final Pattern ASKS_DETAILS = Pattern.compile("(?:الاسم|العنوان|رقم\\s+(?:موبايل|الموبايل|تليفون|التليفون|واتساب)|هحجزهولك|هجهزلك|هجهزهالك|your\\s+name|address)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SIZE_BEFORE = Pattern.compile("(?:لابس|لابسه|مقاس|مقاسي|مقاسه|نمره|نمرتي|نمرته|سن|سنه|عمره|عمرها|عمري|وزني|وزنه|وزنها|طولي|طوله|طولها|size)\\s*$", Pattern.CASE_INSENSITIVE);

    private static void quantity(String customer, String answer, List<Mention> ms, String only, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        if (customer == null) return;
        // a number inside a product's name («موبايل نوفا ميني 8», a code) is not a quantity: the names the customer wrote are taken out first
        String lc = light(customer);
        for (Mention mn : mentions(customer, shop.names())) lc = lc.substring(0, mn.start()) + " ".repeat(mn.end() - mn.start()) + lc.substring(mn.end());
        Matcher m = ASKED_QTY.matcher(lc);
        boolean found = false;
        while (m.find()) {
            String before = lc.substring(0, m.start());
            if (ADDRESS_BEFORE.matcher(before).find() || SIZE_BEFORE.matcher(before).find()) continue;   // «شارع ٩ عمارة ١٢» is an address, «لابسة ٤٢» a size
            String prev = before.trim().replaceAll(".*\\s", "");
            if (!prev.isEmpty() && shop.names().stream().anyMatch(n -> light(n.name()).contains(prev + " " + m.group(1)))) continue;   // «زينترا بوك 14»: part of a name
            found = true;
            break;
        }
        if (!found) return;
        int asked = Integer.parseInt(m.group(1));
        if (asked < 3) return;
        // the product the customer named decides (a reply about another one, «جينز سكيني ممزق … 8 قطع» for «… هاي ويست», is no answer)
        var named = mentions(customer, shop.names()).stream().map(Mention::slug).distinct().toList();
        String slug = named.size() == 1 ? named.get(0) : ms.size() == 1 ? ms.get(0).slug() : only;
        if (slug == null) return;
        Product p = load.apply(slug);
        if (p.variants().isEmpty()) return;
        String n = String.valueOf(asked);   // «٥١٢ ذهبي» is the 512 GB option, not 512 pieces
        if (p.variants().stream().flatMap(v -> v.options().values().stream()).anyMatch(v -> Pattern.compile("(?<!\\d)" + n + "(?!\\d)").matcher(light(v)).find())) return;
        String l = light(answer);
        // the options the customer chose decide the stock; the reply's values count only for an option the customer left out («متاحة بالمقاسات S, M, L» is not the size asked)
        Map<String, String> want = new LinkedHashMap<>(valuesIn(light(customer), p));
        for (var e : valuesIn(l, p).entrySet()) if (!want.containsValue(e.getValue())) want.put(e.getKey(), e.getValue());
        // «أبيض ووردي ١٢٨ جيجا»: two values of one option are either of them
        Map<String, Set<String>> byOption = new LinkedHashMap<>();
        for (var e : want.entrySet()) byOption.computeIfAbsent(e.getValue(), k -> new java.util.HashSet<>()).add(e.getKey());
        int stock = p.variants().stream().filter(v -> byOption.entrySet().stream().allMatch(e -> e.getValue().contains(v.options().get(e.getKey())))).mapToInt(Variant::available).sum();
        if (stock >= asked || stock >= MADE_TO_ORDER) return;   // a kitchen's «300» is no real limit; the summary refuses more than the stock anyway
        // the reply has to say the real number («متاح منه ٨ بس», «قطعتين بس»), or that it is sold out when nothing is left; «١٧ قطعة فقط» when 12 are left is wrong
        if (stock > 0 && (Pattern.compile("(?<!\\d)" + stock + "(?!\\d)").matcher(l).find() || stock < STOCK_WORDS.size() && STOCK_WORDS.get(stock).matcher(l).find())) return;
        if (stock <= 0 && NEG_STRICT.matcher(l).find()) return;
        String what = "«" + p.name() + "»" + (want.isEmpty() ? "" : " " + String.join(" ", want.keySet()));
        boolean ar = arabic(answer) || arabic(customer);
        String truth = stock > 0
                ? (ar ? what + " متاح منه " + stock + " بس حالياً 🙏 تحب أجهزلك الـ " + stock + "؟" : p.name() + (want.isEmpty() ? "" : " " + String.join(" ", want.keySet())) + ": only " + stock + " in stock right now 🙏 Shall I get the " + stock + " ready for you?")
                : (ar ? what + " خلص حالياً 🙏 المتاح منه: " + stockLine(p, Map.of()) + "." : p.name() + " is sold out in that option 🙏 In stock: " + stockLine(p, Map.of()) + ".");
        out.add(new Problem("STOCK_QUANTITY", "The customer asked for " + asked + " of " + what + " but only " + stock
                + " are in stock: " + stockLine(p, want) + ". Say exactly how many are available (" + stock + "); never promise more and never give another number.", truth));
    }

    // ---- 11. products the shop does not sell ------------------------------------------------------------------------------

    /** A list line about a service, not an item (a coupon, delivery, payment, returns). */
    private static final Pattern SERVICE_LINE = Pattern.compile("(?:كود|كوبون|خصم|توصيل|شحن|دليفري|تقسيط|كاش|فيزا|استرجاع|استبدال|ضمان|coupon|code|delivery|shipping|payment|return|warranty)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC = Pattern.compile("\\d\\s*(?:gb|tb|mb|\"|”|inch|hz|mah|w(?![a-z])|جيجا|تيرا|بوصه|انش|هرتز|وات|مللي)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_WORD = Pattern.compile("^(?:ال)?(?:لون|مقاس|سعه|حجم|حاله|نسخه|مساحه|خامه)(?!\\p{L})|^(?:colou?r|size|storage)\\b", Pattern.CASE_INSENSITIVE);   // «اللون الأسود (متاح)»
    private static final Pattern PRICE_OR_OFFER = Pattern.compile("(?:\\d{2,}|متاح|متوفر|موجود|بالوان|الوان|available|EGP|جنيه)", Pattern.CASE_INSENSITIVE);

    /**
     * «- سامسونج جالاكسي A16 – متوفر بألوان…» in a shop that has no such phone: two or more list lines offering items (a price, "available", colours) and naming no product
     * of the shop. The model listed things from memory instead of from the shop.
     */
    private static void inventedProducts(String answer, Shop shop, List<Problem> out) {
        if (!mentions(answer, shop.names()).isEmpty()) return;   // a reply about a real product lists its options and details, not other products
        List<String> invented = new ArrayList<>();
        for (String line : answer.split("\\n")) {
            if (!LIST_LINE.matcher(line).find() || !PRICE_OR_OFFER.matcher(light(line)).find() || SERVICE_LINE.matcher(light(line)).find()) continue;
            String rest = line.replaceFirst("^\\s*(?:[-•*▪✅🔸🔹]|\\d+\\s*[-.)]|[٠-٩]+\\s*[-.)])\\s*", "");
            Matcher sep = Pattern.compile("[–—:(،,]").matcher(rest);
            String item = sep.find() ? rest.substring(0, sep.start()).trim() : rest.trim();
            if (sep.hitEnd() || (sep.start() < rest.length() && rest.charAt(sep.start()) == ':')) continue;   // «البطارية: 6000» is a detail, not an item
            if (SPEC.matcher(light(item)).find() || OPTION_WORD.matcher(light(item)).find()) continue;   // «32GB RAM / 1TB SSD», «16" OLED»: a detail of a product, not a product
            if (item.split("\\s+").length < 2 || item.matches(".*\\d.*") && item.split("\\s+").length < 3) continue;   // «128 جيجا», «20 بوصة»: an option
            invented.add(item);
        }
        if (invented.size() < 2) return;
        out.add(new Problem("INVENTED_PRODUCTS", "These are not products of this shop: " + String.join("، ", invented.stream().limit(4).toList())
                + ". Offer only the shop's own products from search_products, with their real names and prices.", null));
    }

    // ---- 12. another product matches what the customer asked better ---------------------------------------------------------

    /**
     * «عايز جراب لنوفا X12 برو» answered with «جراب جلد قلاب نوفا X12» while «جراب نوفا X12 برو ضد الصدمات» exists: products whose name holds every meaningful word the customer
     * wrote exist, and the reply names products but none of them.
     */
    // ---- 13. brands the shop does not sell ---------------------------------------------------------------------------------

    /** Well-known brands, as {@link #light} writes them: a model offers them from memory. */
    private static final Set<String> BRANDS = Set.of("سامسونج", "شاومي", "ريدمي", "هونور", "هواوي", "ايفون", "ابل", "اوبو", "ريلمي", "فيفو", "نوكيا", "انفينكس", "تكنو", "لينوفو", "ديل", "اسوس",
            "ايسر", "سوني", "توشيبا", "شارب", "بيكسل", "انكر", "جي بي ال", "نايك", "اديداس", "زارا", "بوما", "ديفاكتو", "ال سي وايكيكي",
            "samsung", "xiaomi", "redmi", "honor", "huawei", "iphone", "apple", "oppo", "realme", "vivo", "nokia", "infinix", "tecno", "lenovo", "dell", "asus", "acer", "sony", "toshiba",
            "pixel", "anker", "jbl", "nike", "adidas", "zara", "puma", "defacto", "lc waikiki");
    private static final Pattern OFFERED = Pattern.compile("(?:عندنا|عندي|متاح|متوفر|موجود|بسعر|جنيه|نوفرلك|اوريك|we\\s+have|available|egp)", Pattern.CASE_INSENSITIVE);

    /** «لكن عندنا تاني موديلات من هونور أو شاومي» in a shop that has no such brand: a brand offered that no product of the shop is. A sentence that denies it is fine. */
    private static void inventedBrands(String answer, Shop shop, List<Problem> out) {
        String l = light(answer).toLowerCase(java.util.Locale.ROOT);
        Set<String> invented = new LinkedHashSet<>();
        for (int[] s : sentences(answer)) {
            String sentence = l.substring(s[0], s[1]);
            if (!OFFERED.matcher(sentence).find() || NEG.matcher(sentence).find()) continue;
            for (String b : BRANDS) {
                if (!Pattern.compile("(?<![\\p{L}])(?:ب|و|من\\s+|لـ?)?" + Pattern.quote(b) + "(?![\\p{L}])").matcher(sentence).find()) continue;
                if (shop.searchAll(List.of(b)).isEmpty()) invented.add(b);
            }
        }
        if (invented.isEmpty()) return;
        out.add(new Problem("INVENTED_PRODUCTS", "This shop does not sell " + String.join("، ", invented) + ": never offer or name brands it does not have; offer only the shop's own products from search_products, with their real names and prices.", null));
    }

    // ---- 14. the answer lock: what the customer asked about a product they named is in the reply, right -----------------

    private static final Pattern ASK_PRICE = Pattern.compile("(?:(?<!\\p{L})بكام(?!\\p{L})|(?<!\\p{L})بكم(?!\\p{L})|سعر|تمن|ثمن|how\\s+much|price|cost)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_COLOURS = Pattern.compile("(?:الوان|الالوان|لون\\s+ايه|لونه\\s+ايه|بالوان\\s+ايه|colou?rs|what\\s+colou?r)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_SIZES = Pattern.compile("(?:المقاسات|مقاسات|مقاساته|مقاس\\s+ايه|بمقاسات\\s+ايه|sizes|what\\s+size)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_STOCK = Pattern.compile("(?:متاح|متوفر|موجود|عندكم|عندك|فيه|في\\s+منه|available|in\\s+stock|do\\s+you\\s+have)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAYS_STOCK = Pattern.compile("(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده|عندنا|available|in\\s+stock|we\\s+have)", Pattern.CASE_INSENSITIVE);

    /**
     * The customer named one product and asked its price, its colours or sizes, or whether an option of it is there: the reply has to hold that answer from the data. A reply that
     * leaves it out («السعر بيختلف حسب الموديل», «تحب أشوفلك الألوان؟», «باللون الأسود فقط» when three colours are in stock) gets the answer written by code. An option the product
     * does not have («شاورما … عيش بلدي» when the bread is صاج / سوري / فينو) must not be agreed to. Works for any product of any shop.
     */
    private static void answerLock(String customer, String answer, List<Mention> ms, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        if (customer == null || out.stream().anyMatch(x -> (x.kind().equals("OPTION_PRICE") || x.kind().equals("OPTION_STOCK")) && x.fixed() != null)) return;   // a wrong fact the other checks already put right
        var asked = mentions(customer, shop.names()).stream().map(Mention::slug).distinct().toList();
        if (asked.size() != 1) return;
        Product p = load.apply(asked.get(0));
        if (p.variants().isEmpty()) return;
        String lc = light(customer), la = light(answer);
        String question = lc;   // the question without the product's own name («توب قطن بكم طويل» holds «بكم» and asks nothing)
        for (Mention m : mentions(customer, shop.names())) question = question.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + question.substring(m.end());
        question = question.replaceAll("(?:سعر|سعرات)\\s+(?:حراري|حراريه)|كالوري|calories|kcal", " ");   // «فيه كام سعر حراري؟» asks the calories, not the price
        boolean ar = arabic(answer);
        Map<String, String> picked = valuesIn(lc, p);   // the options the customer named
        List<Variant> fit = p.variants().stream().filter(v -> v.options().values().containsAll(picked.keySet())).toList();
        List<String> nums = new ArrayList<>();
        Matcher nm = Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d{1,2})?").matcher(la.replaceAll("(?<=\\d)[,٬](?=\\d{3})", ""));
        while (nm.find()) nums.add(nm.group());
        // an option of the same kind that this product does not have («فرد», «عيش بلدي», «رمادي»): never agreed to
        String foreign = foreignValue(lc, p, shop, load);
        String fkey0 = foreign == null ? null : foreignKey(p, foreign, shop, load);
        if (foreign == null) { String[] c = foreignCount(customer, question, p); if (c != null) { foreign = c[0]; fkey0 = c[1]; } }   // «ناجتس ٢ قطعة»
        if (foreign != null) {
            String fkey = fkey0;
            Set<String> ownOfKey = new java.util.HashSet<>();
            for (Variant v : p.variants()) if (fkey != null && v.options().containsKey(fkey)) ownOfKey.add(light(v.options().get(fkey)));
            List<String> fw = new ArrayList<>();
            for (Tok t : tokens(light(foreign), 0, foreign.length())) fw.add(t.word());
            // «كبيرة ١.٥ لتر» is said as «١.٥ لتر» too: a value with a number is found by the number and what follows it
            for (int i = 0; i < fw.size(); i++) if (fw.get(i).chars().allMatch(Character::isDigit)) { if (i > 0) fw.subList(0, i).clear(); break; }
            boolean agreed = false;
            List<int[]> agreeing = new ArrayList<>();
            boolean saysYes = YES_FIRST.matcher(la).find();   // «أيوه، … بيقرب من فري سايز»
            boolean sizes = fkey != null && sizeOption(fkey);
            for (int[] sn : sentences(answer)) {
                String se = la.substring(sn[0], sn[1]);
                if (ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && !m.slug().equals(p.slug()))) continue;   // «عندنا ساندوتش كفتة بيجي بعيش بلدي»: another product that has it
                String under = lastBefore(ms, sn[0], null);   // «- الألوان: أسود، أبيض» under «2. ساعة ذكية بلس 5»: that product's line
                if (ms.stream().noneMatch(m -> m.start() >= sn[0] && m.start() < sn[1]) && under != null && !under.equals(p.slug())) continue;
                List<Tok> st = tokens(se, 0, se.length());
                boolean has = phraseIn(st, fw);
                // «المقاس 40 عادةً هو M»: a size conversion, not a yes; «العيش المتاح عيش بلدي أو فينو» lists it with the real ones: a yes
                boolean mapped = sizes && !saysYes && ownOfKey.stream().anyMatch(o -> st.stream().anyMatch(t -> same(t.word(), o)));
                // the clause that holds it decides («الوصف مش بيحدد نوع العيش، وعادةً بييجي بعيش سوري» is a yes to «عيش سوري»)
                String cl = has ? clauseOf(se, st, fw) : se;
                if (has && (NEG_STRICT.matcher(cl).find() || WITHOUT.matcher(cl).find())) break;   // «مفيش وردي», «… أو ١ لتر بس، من غير ١.٥ لتر»: said no; what follows are other choices
                if (has && (CONDITIONAL.matcher(se).find() || ALTERNATIVE.matcher(cl).find())) continue;   // «أقولك لما التركواز يوصل»
                if (has && !mapped && (YES.matcher(cl).find() || saysYes || YES.matcher(se).find() && !NEG_STRICT.matcher(se).find())) { agreed = true; agreeing.add(sn); }
            }
            if (agreed) {
                String truth = ar ? "«" + p.name() + "» مش متاح «" + foreign + "»؛ المتاح: " + optionsLine(p, fkey) + "."
                                  : p.name() + " does not come in " + foreign + "; it comes in: " + optionsLine(p, fkey) + ".";
                out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» does NOT come in «" + foreign + "»; never agree to it. Its options: " + optionsLine(p, fkey) + ".", (truth + "\n" + withoutRanges(answer, agreeing)).trim()));
                return;
            }
            // not agreed, but not answered either («هل عايز تعرف توافر الرمادي؟», «تحب العادي ولا الكبير؟», «ده مقاس كبير على اللي عندنا»): the reply has to say no to it or name the
            // real values of that option; otherwise code says both, and the sentences that still offer the value go
            if (fkey != null && !foreignAnswered(answer, la, ms, p, fkey, fw)) {
                List<int[]> offering = new ArrayList<>(unsureSentences(answer));
                for (int[] sn : sentences(answer)) {
                    List<Tok> st = tokens(la.substring(sn[0], sn[1]), 0, sn[1] - sn[0]);
                    if (phraseIn(st, fw)) offering.add(sn);
                }
                // «الساندوتش ده مش من أصنافنا», «مفيش كيبورد لاسلكي زينترا»: the product itself denied while it is there — those go too, and code says it is there with its price
                boolean deniedItself = false;
                if (p.variants().stream().anyMatch(v -> v.available() > 0))
                    for (int[] sn : sentences(answer)) {
                        boolean other = ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && !m.slug().equals(p.slug()));
                        String se = la.substring(sn[0], sn[1]);
                        boolean itself = ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && m.slug().equals(p.slug()));
                        if (!other && (NEG_STRICT.matcher(se).find() || DENY.matcher(se).find() || SELLS_NOT.matcher(se).find() || !itself && ONLY_CLAIM.matcher(se).find())) { offering.add(sn); deniedItself = true; }
                    }
                offering.sort(java.util.Comparator.comparingInt(a -> a[0]));
                String truth = deniedItself
                        ? (ar ? "أيوه عندنا " + priceLine(p, true) + " ✅ بس مش متاح «" + foreign + "» 🙏 المتاح: " + optionsLine(p, fkey) + "."
                              : "Yes, we have " + priceLine(p, false) + " ✅ but not in " + foreign + " 🙏 It comes in: " + optionsLine(p, fkey) + ".")
                        : ar ? "«" + p.name() + "» مش متاح «" + foreign + "» 🙏 المتاح: " + optionsLine(p, fkey) + "."
                             : p.name() + " does not come in " + foreign + " 🙏 It comes in: " + optionsLine(p, fkey) + ".";
                out.add(new Problem("OPTION_STOCK", "The customer asked for «" + p.name() + "» in «" + foreign + "», which it does NOT have: say so plainly and name its real options: " + optionsLine(p, fkey) + ".",
                        (truth + "\n" + withoutRanges(answer, offering)).trim()));
                return;
            }
            // a no, but not what it does come in («مش من الأصناف اللي عندنا، بس عندنا أحجام تانية»): code adds its real options
            if (fkey != null && !ownListed(la, p, fkey) && !optionsLine(p, fkey).isBlank()) {
                String line = ar ? "المتاح من «" + p.name() + "»: " + optionsLine(p, fkey) + "." : p.name() + " comes in: " + optionsLine(p, fkey) + ".";
                out.add(new Problem("OPTIONS_MISSING", "You said «" + p.name() + "» does not come in «" + foreign + "»: also name what it does come in: " + optionsLine(p, fkey) + ".", answer.strip() + "\n\n" + line));
                return;
            }
        }
        boolean unsure = UNSURE.matcher(la).find();   // «مفيش تأكيد على المخزون», «مش لاقي لها بيانات سعر»: no answer at all
        String sure = UNSURE.matcher(la).replaceAll(" ");   // what the reply says plainly («غير متوفرة» stays; «نتحقق من…» and «مفيش تأكيد» go)
        // the customer wants an option combination that is sold out and the reply never says so: code says it
        if (!picked.isEmpty() && !fit.isEmpty() && fit.stream().allMatch(v -> v.available() <= 0) && !NEG_STRICT.matcher(sure).find() && !STOCK_NEG.matcher(sure).find()) {
            String what = "«" + p.name() + "» " + String.join(" ", picked.keySet());
            String line = ar ? what + " خلص حالياً 🙏 المتاح منه: " + stockLine(p, Map.of()) + "." : p.name() + " in " + String.join(" ", picked.keySet()) + " is sold out right now 🙏 In stock: " + stockLine(p, Map.of()) + ".";
            out.add(new Problem("OPTION_STOCK", what + " is SOLD OUT (0 in stock): say so first, then offer what is in stock: " + stockLine(p, Map.of()) + ".", (line + "\n" + withoutRanges(answer, unsureSentences(answer))).trim()));
            return;
        }
        if (ASK_PRICE.matcher(question).find() && !fit.isEmpty()) {
            Set<String> ok = new java.util.HashSet<>();
            for (Variant v : p.variants()) { ok.add(num(v.price(), false)); if (v.before() > 0) ok.add(num(v.before(), false)); }
            boolean hasOwn = fit.stream().anyMatch(v -> nums.contains(num(v.price(), false)));
            boolean soldOutSaid = fit.stream().allMatch(v -> v.available() <= 0) && NEG.matcher(sure).find();   // the price of a sold-out option: «خلص» is the answer
            if (!hasOwn && !soldOutSaid) {
                java.util.TreeSet<Double> fp = new java.util.TreeSet<>();
                for (Variant v : fit) fp.add(v.price());
                String line = fp.size() == 1 ? (ar ? "«" + p.name() + "»" + (picked.isEmpty() ? "" : " " + String.join(" ", picked.keySet())) + " بـ " + num(fp.first(), false) + " جنيه."
                                                   : p.name() + (picked.isEmpty() ? "" : " " + String.join(" ", picked.keySet())) + " is " + num(fp.first(), false) + " EGP.")
                                             : priceLine(p, ar) + ".";
                out.add(new Problem("PRICE_MISSING", "The customer asked the price of «" + p.name() + "»: give it exactly: " + line, (line + "\n" + withoutRanges(answer, unsureSentences(answer))).trim()));
                return;
            }
        }
        for (boolean colours : new boolean[]{true, false}) {
            if (!(colours ? ASK_COLOURS : ASK_SIZES).matcher(question).find()) continue;
            Set<String> inStock = new LinkedHashSet<>();
            for (Variant v : p.variants()) if (v.available() > 0) v.options().forEach((k, val) -> { if (colours ? colorOption(k) : sizeOption(k)) inStock.add(val); });
            if (inStock.isEmpty()) continue;
            List<String> missing = inStock.stream().filter(v -> !la.contains(bare(light(v)).split(" ")[0])).toList();
            if (missing.isEmpty()) continue;
            String line = ar ? "«" + p.name() + "» متاح " + (colours ? "بالألوان: " : "بالمقاسات: ") + String.join("، ", inStock) + "."
                             : p.name() + " is available in " + String.join(", ", inStock) + ".";
            out.add(new Problem("OPTIONS_MISSING", "The customer asked the " + (colours ? "colours" : "sizes") + " of «" + p.name() + "»: in stock: " + String.join("، ", inStock) + ". Name them all.", (line + "\n" + withoutRanges(answer, unsureSentences(answer))).trim()));
            return;
        }
        boolean stockSaid = false;   // a plain sentence about it says it is there or not («عندنا أنواع تانية…», «لو تحب… متاحة؟» are other choices)
        for (int[] sn : sentences(answer)) {
            if (UNSURE.matcher(la.substring(sn[0], sn[1])).find()) continue;   // «مش عندي تأكيد على توفر …، ومش كلام تقريبي»: not knowing is no answer, whatever else it holds
            String se = la.substring(sn[0], sn[1]);
            boolean itself = ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && m.slug().equals(p.slug()));
            if (!itself && ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1])) continue;   // a sentence about another product
            if (!itself && HOURS_TALK.matcher(se).find() && !SAYS_STOCK.matcher(se).find()) continue;   // «مش لسه بنفتح دلوقتي»: the hours, not the product
            // a no counts when it is about the product: its name, an option the customer picked or a stock word («الرابط مش شغال» says nothing about the stock)
            List<Tok> st = tokens(se, 0, se.length());
            boolean aboutIt = itself || STOCK_NEG.matcher(se).find() || SAYS_STOCK.matcher(se).find()
                    || picked.keySet().stream().anyMatch(v -> tokens(light(v), 0, v.length()).stream().allMatch(w -> st.stream().anyMatch(t -> same(t.word(), w.word()))));
            if (NEG_STRICT.matcher(se).find() && aboutIt || SAYS_STOCK.matcher(se).find() && !OTHER_CHOICES.matcher(se).find() && !CONDITIONAL.matcher(se).find()) { stockSaid = true; break; }
        }
        if ((!picked.isEmpty() || unsure && foreign == null) && ASK_STOCK.matcher(question).find() && !stockSaid) {
            int stock = fit.stream().mapToInt(Variant::available).sum();
            String what = "«" + p.name() + "»" + (picked.isEmpty() ? "" : " " + String.join(" ", picked.keySet()));
            String line = stock > 0 ? (ar ? what + " متاح ✅" : what + " is available ✅") : (ar ? what + " خلص حالياً 🙏 المتاح منه: " + stockLine(p, Map.of()) + "." : what + " is sold out 🙏 In stock: " + stockLine(p, Map.of()) + ".");
            out.add(new Problem("STOCK_MISSING", "The customer asked whether " + what + " is available: " + (stock > 0 ? "it is." : "it is SOLD OUT.") + " Say so plainly.", (line + "\n" + withoutRanges(answer, unsureSentences(answer))).trim()));
        }
    }

    /**
     * The reply says no to a value the product does not have: a sentence about it holds the value and a no («مفيش رمادي»), or the reply names every value of that option it has in stock
     * («الألوان: أسود وفضي بس»), or for numbered values the first and the last («المقاسات من ٢٦ لـ ٣٢»).
     */
    private static boolean foreignAnswered(String answer, String la, List<Mention> ms, Product p, String key, List<String> fw) {
        return saidNoTo(answer, la, ms, p, fw) || ownListed(la, p, key);
    }

    /** A sentence about the product holds the value and a no («مفيش رمادي», «من غير XXL»). */
    private static boolean saidNoTo(String answer, String la, List<Mention> ms, Product p, List<String> fw) {
        for (int[] sn : sentences(answer)) {
            boolean other = ms.stream().anyMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && !m.slug().equals(p.slug()))
                    && ms.stream().noneMatch(m -> m.start() >= sn[0] && m.start() < sn[1] && m.slug().equals(p.slug()));
            if (other) continue;   // «X12 مش متاح بالرمادي» is about the X12
            String se = UNSURE.matcher(la.substring(sn[0], sn[1])).replaceAll(" ");   // «مش لاقي تأكيد إنه متوفر بالأحمر» is no no
            List<Tok> st = tokens(se, 0, se.length());
            if (!phraseIn(st, fw)) continue;
            String cl = clauseOf(se, st, fw);
            if (NEG_STRICT.matcher(cl).find() || STOCK_NEG.matcher(cl).find() || DENY.matcher(cl).find() || WITHOUT.matcher(cl).find()) return true;
        }
        return false;
    }

    /** The reply names the values of that option the product has: all of them in stock, a range of two of them, the first and the last, or the smallest with «أصغر». */
    private static boolean ownListed(String la, Product p, String key) {
        Set<String> own = new LinkedHashSet<>();
        for (Variant v : p.variants()) if (v.available() > 0 && v.options().containsKey(key)) own.add(light(v.options().get(key)));
        if (own.isEmpty()) for (Variant v : p.variants()) if (v.options().containsKey(key)) own.add(light(v.options().get(key)));
        if (own.isEmpty()) return true;
        List<Tok> all = tokens(la, 0, la.length());
        if (own.stream().allMatch(v -> tokens(v, 0, v.length()).stream().allMatch(w -> all.stream().anyMatch(t -> same(t.word(), w.word()))))) return true;
        List<String> ordered = new ArrayList<>(own);
        if (ordered.size() >= 2) {
            String gap = "[^.؟?!\\n\\d]{0,22}?(?<![\\p{L}\\p{N}])";
            String any = ordered.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));   // the shop's order of values is not always smallest first
            if (Pattern.compile("(?:" + LIMIT_WORDS + "|" + START_WORDS + ")" + gap + "(?:" + any + ")(?![\\p{L}\\p{N}])").matcher(la).find()) return true;
        }
        // «متاح من مقاس S لحد XL بس», «S لغاية XXL»: a range between two of its own values
        Matcher r = RANGE.matcher(la);
        while (r.find()) { String a = bare(r.group(1)), b = bare(r.group(2)); if (!a.equals(b) && own.stream().anyMatch(o -> o.equals(a)) && own.stream().anyMatch(o -> o.equals(b))) return true; }
        List<Double> numbers = new ArrayList<>();
        for (String v : own) { Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)").matcher(v.trim()); if (!m.find()) { numbers = null; break; } numbers.add(Double.parseDouble(m.group(1))); }
        if (numbers != null && numbers.size() >= 2) {
            String lo0 = num(java.util.Collections.min(numbers), false);
            if (Pattern.compile("(?<!\\p{L})(?:ال)?(?:اصغر|اقل)(?!\\p{L})[^.؟?!\\n]{0,25}?(?<![\\d.])" + Pattern.quote(lo0) + "(?![\\d])").matcher(la).find()) return true;
        }
        if (numbers != null && numbers.size() >= 3) {
            String lo = num(java.util.Collections.min(numbers), false), hi = num(java.util.Collections.max(numbers), false);
            return Pattern.compile("(?<![\\d.])" + Pattern.quote(lo) + "(?![\\d])").matcher(la).find() && Pattern.compile("(?<![\\d.])" + Pattern.quote(hi) + "(?![\\d])").matcher(la).find();
        }
        return false;
    }

    private static final Pattern WITHOUT = Pattern.compile("(?<!\\p{L})(?:من\\s+غير|بدون|ماعدا|عدا|الا|except|without)(?!\\p{L})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:من\\s+)?(?:مقاس\\s+|المقاس\\s+|سن\\s+)?([\\p{L}\\p{N}]+)[\\s\\u200C]*(?:لحد|لغايه|لغايت|الي|إلى|ل|-|–|to)[\\s\\u200C]*(?:مقاس\\s+)?([\\p{L}\\p{N}]+)", Pattern.CASE_INSENSITIVE);
    /** «لحد مقاس XL», «أكبر مقاس فيه XL», «بيوقف عند XL»: its last value with a word that says it is the last; «بيبدأ من مقاس ٤٠»: the first with one that says it is the first. */
    private static final String LIMIT_WORDS = "(?:لحد|لغايه|حتي|اقصي|اكبر|الاكبر|بيوقف\\s+عند|بيخلص\\s+عند|بتوصل|بيوصل|توصل|يوصل)", START_WORDS = "(?:يبدا|بيبدا|تبدا|بتبدا|بتبتدي|بيبتدي|ابتداء|اصغر|الاصغر|اقل)";

    /** «رمادية», «الرمادي», «بالرمادي» as «رمادي». */
    private static String colourWord(String w) {
        String b = bare(w);
        return b.length() >= 5 && b.endsWith("يه") ? b.substring(0, b.length() - 1) : b;
    }

    /**
     * «نوفا X12 عنده اللون الفضي والرمادي»: a colour said right after a product's name, in the same sentence and before the next product, that the product does not come in (a colour
     * some product of the shop has, so it is a colour word). A no, a condition or a question about it is fine. Code writes the real colours in place of that sentence.
     */
    private static void otherColours(String answer, List<Mention> ms, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
        if (ms.isEmpty()) return;
        Set<String> shopColours = new java.util.HashSet<>();
        for (Named n : shop.names()) for (Variant v : load.apply(n.slug()).variants())
            v.options().forEach((k, val) -> { String lv = light(val).trim(); if (colorOption(k) && !lv.contains(" ")) shopColours.add(colourWord(lv)); });
        if (shopColours.isEmpty()) return;
        String l = light(answer);
        List<int[]> ss = sentences(answer);
        for (int i = 0; i < ms.size(); i++) {
            Mention m = ms.get(i);
            Product p = load.apply(m.slug());
            Set<String> own = new java.util.HashSet<>();
            boolean hasColour = false;
            for (Variant v : p.variants()) for (var e : v.options().entrySet()) if (colorOption(e.getKey())) { hasColour = true; for (Tok t : tokens(light(e.getValue()), 0, e.getValue().length())) own.add(colourWord(t.word())); }
            if (!hasColour) continue;
            for (Tok t : tokens(light(p.name()), 0, p.name().length())) own.add(colourWord(t.word()));   // «سنيكرز جلد أبيض رجالي»: its name
            int end = l.length(), from = 0;
            for (int[] sn : ss) if (sn[0] <= m.start() && m.start() < sn[1]) { from = sn[0]; end = sn[1]; }
            if (i + 1 < ms.size()) end = Math.min(end, ms.get(i + 1).start());
            if (end <= m.end()) continue;
            String seg = l.substring(m.end(), end), whole = l.substring(from, end);   // «مفيش شاحن فولتا 20 وات فضي»: the no comes before the name
            if (NEG_STRICT.matcher(whole).find() || STOCK_NEG.matcher(whole).find() || DENY.matcher(whole).find() || UNSURE.matcher(whole).find()) continue;
            for (Tok t : tokens(seg, 0, seg.length())) {
                String c = colourWord(t.word());
                if (!shopColours.contains(c) || own.contains(c)) continue;
                String before = seg.substring(0, t.start());
                if (NEG_STRICT.matcher(seg).find() || STOCK_NEG.matcher(seg).find() || DENY.matcher(seg).find() || Pattern.compile("(?<!\\p{L})(?:مش|غير|لو|ولا|بدل|بدال|من\\s+غير|if|not|instead)(?!\\p{L})").matcher(before).find() || seg.contains("؟") || seg.contains("?")) break;
                int at = m.start();
                int[] sentence = ss.stream().filter(sn -> sn[0] <= at && at < sn[1]).findFirst().orElse(new int[]{m.start(), end});
                Set<String> colours = new LinkedHashSet<>();
                for (Variant v : p.variants()) if (v.available() > 0) v.options().forEach((k, val) -> { if (colorOption(k)) colours.add(val); });
                if (colours.isEmpty()) break;
                String truth = arabic(answer) ? "«" + p.name() + "» ألوانه المتاحة: " + String.join("، ", colours) + "." : p.name() + " comes in: " + String.join(", ", colours) + ".";
                out.add(new Problem("INVENTED_COLOR", "«" + p.name() + "» does not come in «" + t.word() + "»; its colours: " + String.join("، ", colours) + ". Say only those.",
                        (truth + "\n" + withoutRanges(answer, List.<int[]>of(sentence))).trim()));
                return;
            }
        }
    }

    /** The reply without the given sentences (the ones a code-written truth replaces). */
    static String withoutRanges(String answer, List<int[]> drop) {
        if (drop.isEmpty()) return answer;
        StringBuilder b = new StringBuilder();
        int at = 0;
        for (int[] s : drop) { if (s[0] < at) continue; b.append(answer, at, s[0]); at = s[1]; }
        b.append(answer.substring(at));
        return b.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** The sentences that only say the assistant does not know («مفيش تأكيد على المخزون»): they go when code writes the answer. */
    private static List<int[]> unsureSentences(String answer) {
        String l = light(answer);
        List<int[]> out = new ArrayList<>();
        for (int[] s : sentences(answer)) if (UNSURE.matcher(l.substring(s[0], s[1])).find()) out.add(s);
        return out;
    }

    private static final Pattern HOURS_TALK = Pattern.compile("(?<!\\p{L})(?:بنفتح|نفتح|بنفتتح|نفتتح|الفتح|يفتح|مقفول|مقفوله|مغلق|مقفل|قافل|قافلين|مواعيد|open|opens|closed)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    /** «عندنا أنواع تانية», «أقترح عليك خيارات متاحة»: other choices offered, not the one asked. */
    private static final Pattern OTHER_CHOICES = Pattern.compile("(?<!\\p{L})(?:اقرب|ادورلك|ادور|تاني|تانيه|تانين|تانيين|غيره|غيرها|بديل|بدايل|اقترح|اقترحلك|ارشح|ارشحلك|اشوفلك|other|another|alternatives?)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    /** A sentence that starts with «لو» / "if": a condition, not a claim. */
    private static final Pattern CONDITIONAL = Pattern.compile("^[^\\p{L}\\d]*(?:و|ف)?(?:لو|اذا|إذا|if)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    /** A reply that opens with yes. */
    private static final Pattern YES_FIRST = Pattern.compile("^[^\\p{L}\\d]*(?:ايوه|ايوة|اه|اكيد|طبعا|yes|sure|of\\s+course)(?!\\p{L})", Pattern.CASE_INSENSITIVE);
    /** Not knowing instead of answering: «مفيش تأكيد حالي على المخزون», «مش قادر أتأكد», «مش لاقي لها بيانات سعر», «حسب آخر تحديث», "I can't confirm". */
    static final Pattern UNSURE = Pattern.compile("(?:(?:مفيش|مافيش|معنديش|معندناش|مش\\s+عندي|مش\\s+عندنا|مش\\s+لاقي|ملقتش)\\s+(?:اي\\s+)?تاكيد|مش\\s+قادر(?:ين)?\\s+(?:ا|ن)?(?:تاكد|اكد|احدد|حدد|اعرف|عرف|اقول|قول)|(?:ا|ن)تاكد\\s+(?:من|لك|ليك)|(?:ا|ن)?تحقق\\s+من|(?:مش\\s+لاقي|ملقتش|مفيش|مافيش|معنديش|معندناش|مش\\s+معايا|معييش|معيش)\\s+(?:\\p{L}+\\s+)?بيانات|اخر\\s+تحديث|مش\\s+ظاهر|(?:الرابط|اللينك|اللنك)\\s+(?:اللي\\s+)?(?:[\\p{L}\\u200C]+\\s+)?(?:مش|مبيفتحش|مش\\s+بيفتح)\\s*(?:شغال|شغاله|بيفتح|واضح|ظاهر)?|can'?t\\s+confirm|cannot\\s+confirm|not\\s+sure|no\\s+(?:data|information))", Pattern.CASE_INSENSITIVE);
    private static final Pattern YES = Pattern.compile("(?:(?<!\\p{L})(?:و)?(?:بي?يجي|بتيجي)(?!ش)|متاح|متوفر|موجود|هنجهز|هجهز|حاضر|تمام|جاهز|مطلوب|ضفت|اضفت|هضيف|سجلت|ممكن\\s+يكون|غالبا|ايوه|ايوة|اه(?!\\p{L})|اكيد|زي\\s+ما|طبعا|available|sure|yes)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PORTION_WORDS = Set.of("فرد", "فردي", "عائلي", "شخص", "شخصين");

    /** A value in the customer's words that other products of the shop have for an option this product has too, but this product does not («شاورما … عيش بلدي»). */
    private static String foreignValue(String lightCustomer, Product p, Shop shop, java.util.function.Function<String, Product> load) {
        Map<String, Set<String>> own = new LinkedHashMap<>();
        for (Variant v : p.variants()) v.options().forEach((k, val) -> own.computeIfAbsent(light(k), x -> new java.util.HashSet<>()).add(light(val)));
        if (own.isEmpty()) return null;
        Set<String> anyOwnWord = new java.util.HashSet<>();
        List<List<String>> ownValueWords = new ArrayList<>();
        for (var vals : own.values()) for (String v : vals) { List<String> vw = new ArrayList<>(); for (Tok t : tokens(v, 0, v.length())) { anyOwnWord.add(t.word()); vw.add(t.word()); } ownValueWords.add(vw); }
        String rest = lightCustomer;
        for (Mention m : mentions(lightCustomer, shop.names())) rest = rest.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + rest.substring(m.end());   // not the product's own name
        List<Tok> toks = tokens(rest, 0, rest.length());
        Set<String> words = new java.util.HashSet<>();
        for (Tok t : toks) words.add(t.word());
        String best = null;
        Set<String> ownName = new java.util.HashSet<>();
        for (Tok t : tokens(light(p.name()), 0, p.name().length())) ownName.add(t.word());
        for (Named n : shop.names()) {
            if (n.slug().equals(p.slug())) continue;
            Product other = load.apply(n.slug());
            // «تشيز برجر وبيبسي كانز»: the customer talks about that other product too (another of its values or its own name word), so «كانز» is its size, not the burger's
            Set<String> hints = new java.util.HashSet<>();
            for (String w : n.words()) if (!ownName.contains(w) && !STOP.contains(w) && w.length() >= 3) hints.add(w);
            for (Variant v : other.variants()) for (var e : v.options().entrySet())
                if (!own.containsKey(light(e.getKey()))) for (Tok t : tokens(light(e.getValue()), 0, e.getValue().length())) if (t.word().length() >= 3 && !anyOwnWord.contains(t.word())) hints.add(t.word());
            if (toks.stream().anyMatch(t -> hints.stream().anyMatch(h -> same(t.word(), h)))) continue;
            for (Variant v : other.variants())
                for (var e : v.options().entrySet()) {
                    Set<String> mine = own.get(light(e.getKey()));
                    if (mine == null || mine.contains(light(e.getValue()))) continue;
                    List<String> w = new ArrayList<>();
                    for (Tok t : tokens(light(e.getValue()), 0, e.getValue().length())) w.add(t.word());
                    // «فرد»: how many it feeds, a question not an option; «عادي» is its own taste, «أحمر» its own «أحمر هادي» (the words side by side in one own value);
                    // «عيش بلدي» is not «عيش سوري», and «١ لتر» is not «١.٥ لتر» (one digit is a word too)
                    if (w.isEmpty() || w.stream().anyMatch(x -> x.length() < 2 && !x.chars().allMatch(Character::isDigit)) || insideOne(w, ownValueWords) || w.stream().anyMatch(PORTION_WORDS::contains)) continue;
                    // «كبيرة ١.٥ لتر» (the water's) is no size of a meal that comes «عادي، دبل»: a measure is compared only with measures
                    boolean measure = false;   // a number with its unit after it («١.٥ لتر»); a bare size number («40») is compared with any size
                    for (int i = 0; i + 1 < w.size(); i++) if (w.get(i).chars().allMatch(Character::isDigit) && !w.get(i + 1).chars().allMatch(Character::isDigit)) measure = true;
                    if (measure && mine.stream().noneMatch(m -> m.chars().anyMatch(Character::isDigit))) continue;
                    // its words side by side («32 قطعة … 256 جيجا» is no «32 جيجا»)
                    boolean all = false;
                    for (int i = 0; i + w.size() <= toks.size() && !all; i++) {
                        boolean here = true;
                        for (int k = 0; k < w.size() && here; k++) here = same(toks.get(i + k).word(), w.get(k));
                        all = here;
                    }
                    // its words all said side by side (so «L» is never read in «XL»; one of its own values holding them was skipped above)
                    if (all && (best == null || e.getValue().length() > best.length())) best = e.getValue();
                }
        }
        return best;
    }

    /** The clause of the sentence (between commas and dashes) where the words stand side by side. */
    private static String clauseOf(String se, List<Tok> st, List<String> w) {
        for (int i = 0; i + w.size() <= st.size(); i++) {
            boolean here = true;
            for (int k = 0; k < w.size() && here; k++) here = same(st.get(i + k).word(), w.get(k));
            if (!here) continue;
            int at = st.get(i).start(), a = at, b = st.get(i + w.size() - 1).end();
            while (a > 0 && "،,—–".indexOf(se.charAt(a - 1)) < 0) a--;
            while (b < se.length() && "،,—–".indexOf(se.charAt(b)) < 0) b++;
            return se.substring(a, b);
        }
        return se;
    }

    /** The words stand side by side, in this order, in the sentence («١ لتر» is not in «١.٥ لتر»: «1 5 لتر»). */
    private static boolean phraseIn(List<Tok> st, List<String> w) {
        for (int i = 0; i + w.size() <= st.size(); i++) {
            boolean here = true;
            for (int k = 0; k < w.size() && here; k++) here = same(st.get(i + k).word(), w.get(k));
            if (here) return true;
        }
        return false;
    }

    /** The words stand side by side, in this order, inside one of the values. */
    private static boolean insideOne(List<String> w, List<List<String>> values) {
        for (List<String> v : values)
            for (int i = 0; i + w.size() <= v.size(); i++) {
                boolean here = true;
                for (int k = 0; k < w.size() && here; k++) here = same(v.get(i + k), w.get(k));
                if (here) return true;
            }
        return false;
    }

    /**
     * «ناجتس ٢ قطعة» for a product that comes in «٦ قطع، ٩ قطع، ٢٠ قطعة»: a number with the unit of one of its options that is none of its values (a count no product of the shop
     * has, so it is no other product's value either). {raw text, option name}, or null. A real value of that option said too («٢ ناجتس ٩ قطع») makes the number a quantity.
     */
    private static String[] foreignCount(String customer, String question, Product p) {
        Map<String, List<double[]>> byKey = new LinkedHashMap<>();
        Map<String, String> unitOf = new LinkedHashMap<>();
        Map<String, Set<String>> valuesOf = new LinkedHashMap<>();
        for (Variant v : p.variants())
            for (var e : v.options().entrySet()) {
                if (unitOf.containsKey(e.getKey()) && unitOf.get(e.getKey()) == null) continue;
                valuesOf.computeIfAbsent(e.getKey(), x -> new LinkedHashSet<>()).add(light(e.getValue()));
                Matcher m = COUNT_VALUE.matcher(light(e.getValue()).trim());
                String u = m.matches() ? countUnit(m.group(2)) : null;
                if (u == null || unitOf.containsKey(e.getKey()) && !u.equals(unitOf.get(e.getKey()))) { unitOf.put(e.getKey(), null); continue; }
                unitOf.put(e.getKey(), u);
                byKey.computeIfAbsent(e.getKey(), x -> new ArrayList<>()).add(new double[]{Double.parseDouble(m.group(1))});
            }
        for (var e : unitOf.entrySet()) {
            if (e.getValue() == null || valuesOf.get(e.getKey()).size() < 2) continue;
            String lc = light(customer);
            if (valuesOf.get(e.getKey()).stream().anyMatch(o -> Pattern.compile("(?<![\\p{L}\\d.])" + Pattern.quote(o) + "(?![\\p{L}\\d])").matcher(lc).find())) continue;
            Matcher m = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(\\p{L}+)").matcher(question);
            while (m.find()) {
                if (!e.getValue().equals(countUnit(bare(m.group(2))))) continue;
                double n = Double.parseDouble(m.group(1));
                if (byKey.get(e.getKey()).stream().anyMatch(x -> x[0] == n)) continue;
                return new String[]{customer.substring(m.start(), m.end()), e.getKey()};
            }
        }
        return null;
    }

    private static final Pattern COUNT_VALUE = Pattern.compile("^(?:\\p{L}+\\s+)?(\\d+(?:\\.\\d+)?)\\s*(\\p{L}+)$");

    /** The unit of an option value, singular and plural as one («قطع», «قطعة» -> «قطع»). */
    private static String countUnit(String w) {
        String b = bare(w);
        return switch (b) {
            case "قطع", "قطعه", "حته", "حتت", "pieces", "piece", "pcs" -> "قطع";
            case "لتر", "litre", "liter", "l" -> "لتر";
            case "مل", "ملي", "ml" -> "مل";
            case "جرام", "جم", "g" -> "جرام";
            case "كيلو", "kg" -> "كيلو";
            case "اصابع", "صباع" -> "صباع";
            default -> b.length() >= 2 ? unit(b) : null;
        };
    }

    private static String foreignKey(Product p, String value, Shop shop, java.util.function.Function<String, Product> load) {
        for (Named n : shop.names()) for (Variant v : load.apply(n.slug()).variants()) for (var e : v.options().entrySet()) if (e.getValue().equals(value)) {
            String k = light(e.getKey());
            for (Variant pv : p.variants()) for (String pk : pv.options().keySet()) if (light(pk).equals(k)) return pk;
        }
        return null;
    }

    /** The values of one option of the product that are in stock («صاج، عيش سوري، فينو»). */
    private static String optionsLine(Product p, String key) {
        Set<String> vals = new LinkedHashSet<>();
        for (Variant v : p.variants()) if (v.available() > 0 && key != null && v.options().containsKey(key)) vals.add(v.options().get(key));
        return vals.isEmpty() ? stockLine(p, Map.of()) : String.join("، ", vals);
    }

    /** Every word of the product's name is in the text, in any order («الطقم … شاحن 20 وات + كابل USB-C لـ Lightning»). */
    private static boolean allWordsOf(String lightText, String slug, Shop shop) {
        Named n = shop.names().stream().filter(x -> x.slug().equals(slug)).findFirst().orElse(null);
        if (n == null || n.words().size() < 2) return false;
        List<Tok> toks = tokens(lightText, 0, lightText.length());
        return n.words().stream().filter(w -> !STOP.contains(w)).allMatch(w -> toks.stream().anyMatch(t -> same(t.word(), w) || t.word().equals(w) || bare(t.word()).equals(w)));
    }

    private static final Set<String> WANT_WORDS = Set.of("عايز", "عايزه", "عاوز", "عاوزه", "محتاج", "محتاجه", "اطلب", "هاخد", "خد", "want", "need", "order");
    private static final Pattern BUDGET_AFTER = Pattern.compile("\\s*(?:الف|الاف|k|جنيه|ج(?!\\p{L})|egp)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUDGET_BEFORE = Pattern.compile("(?:حدود|حوالي|تحت|اقل\\s+من|لحد|ميزانيه|ميزانيتي|budget|under)\\s*$", Pattern.CASE_INSENSITIVE);

    private static void betterMatch(String customer, String answer, List<Mention> ms, Shop shop, List<Problem> out) {
        if (customer == null || ms.isEmpty()) return;
        // «سماعة سونيك بادز برو بتشتغل مع الآيفون؟» answered with other earbuds: the product the customer named by its full name is not in the reply
        var askedAt = mentions(customer, shop.names());
        var asked = askedAt.stream().map(Mention::slug).distinct().toList();
        String la = light(answer);
        List<String> replyWords = tokens(la, 0, la.length()).stream().map(Tok::word).toList();
        boolean repeated = !askedAt.isEmpty() && tokens(light(customer), askedAt.get(0).start(), askedAt.get(0).end()).stream()
                .allMatch(t -> replyWords.stream().anyMatch(w -> same(w, t.word()) || stem(w).equals(stem(t.word())) || t.word().length() >= 4 && w.startsWith(t.word()) && w.length() - t.word().length() <= 2));   // «تيشيرتنا»   // «التيشيرت البناتي»: the reply uses the customer's own words for it
        boolean aboutIt = asked.size() == 1 && !valuesIn(la, shop.product(asked.get(0)) == null ? new Product("", "", 0, List.of()) : shop.product(asked.get(0))).isEmpty() && ms.size() <= 1;   // «أيوه، متوفر باللون الأزرق»
        if (asked.size() == 1 && !repeated && !aboutIt && !allWordsOf(la, asked.get(0), shop) && ms.stream().noneMatch(m -> m.slug().equals(asked.get(0)))) {
            Named n = shop.names().stream().filter(x -> x.slug().equals(asked.get(0))).findFirst().orElse(null);
            String kind = n == null || n.words().isEmpty() ? null : n.words().get(0);
            String lc = light(customer);
            if (kind != null && tokens(lc, 0, lc.length()).stream().anyMatch(t -> same(t.word(), kind) || stem(t.word()).equals(stem(kind)))) {
                out.add(new Problem("BETTER_MATCH", "The customer asked about «" + n.name() + "» (the shop HAS it): answer about it (get_product), never as if it were not there.", null));
                return;
            }
        }
        List<String> words = new ArrayList<>();
        String lcust = light(customer);
        for (Tok t : tokens(lcust, 0, lcust.length())) {
            String w = t.word();
            if (w.chars().allMatch(Character::isDigit) && (BUDGET_AFTER.matcher(lcust.substring(t.end())).lookingAt() || BUDGET_BEFORE.matcher(lcust.substring(0, t.start())).find())) continue;   // «في حدود ١٠ آلاف»
            if (words.contains(w)) continue;
            String prev = lcust.substring(0, t.start()).trim().replaceAll(".*[\\s]", "");
            if (w.chars().allMatch(Character::isDigit) && (prev.isEmpty() || WANT_WORDS.contains(prev))) continue;   // «عايز ١٠ موبايلات»: how many, not which
            if (w.length() >= 4 && w.startsWith("ل") && !w.startsWith("لل")) w = w.substring(1);   // «لنوفا» is «نوفا»
            boolean number = w.chars().allMatch(Character::isDigit);
            if (number && w.length() < 2) continue;   // «X12» keeps its 12; a lone digit says little
            if (w.length() >= 2 && !STOP.contains(w) && !AUDIENCE.contains(w) && !COLORS.contains(w) && !SIZE_CODES.contains(w)
                    && !List.of("عايز", "عايزه", "عاوز", "محتاج", "بكام", "سعر", "كام", "لو", "سمحت", "تمام", "ينفع", "عليه", "بتاع").contains(w)) words.add(w);
        }
        // a word in no product name («للشركة», the quantity) says nothing about which product
        words.removeIf(w -> shop.names().stream().noneMatch(n -> n.words().stream().anyMatch(nw -> same(nw, w) || stem(nw).equals(stem(w)))));
        if (words.size() < 2) return;
        Set<String> named = new java.util.HashSet<>();
        for (Mention m : ms) named.add(m.slug());
        List<Named> exact = new ArrayList<>();
        for (Named n : shop.names()) if (words.stream().allMatch(w -> n.words().stream().anyMatch(nw -> same(nw, w) || stem(nw).equals(stem(w))))) exact.add(n);
        if (exact.isEmpty() || exact.size() > 2 || exact.stream().anyMatch(e -> named.contains(e.slug()) || allWordsOf(light(answer), e.slug(), shop))) return;
        out.add(new Problem("BETTER_MATCH", "The customer asked for «" + String.join(" ", words) + "»: the product that is exactly that is " + String.join("، ", exact.stream().map(Named::name).toList())
                + ". Offer it (search_products for it) instead of a near one.", null));
    }
}

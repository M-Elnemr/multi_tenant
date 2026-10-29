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

    record Named(String slug, String name) {}

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
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\u200C]+|\\p{N}+");

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
        Matcher m = WORD.matcher(lightText).region(from, to);
        while (m.find()) out.add(new Tok(bare(m.group()), m.start(), m.end(), m.group().replace("\u200C", "")));
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
        for (Named n : names) {
            List<String> nw = new ArrayList<>();
            for (Tok t : tokens(light(n.name()), 0, n.name().length())) nw.add(t.word());
            if (nw.isEmpty()) continue;
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
        return false;
    }

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
        optionPrices(answer, ms, only, shop, load, out);
        optionStock(customer, answer, ms, only, load, out);
        falseDenial(answer, shop, out);
        area(answer, shop, out);
        hours(answer, shop, out);
        sale(customer, answer, ms, load, out);
        offTopic(customer, answer, ms, shop, out);
        return out;
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

    private static void optionPrices(String answer, List<Mention> ms, String only, Shop shop, java.util.function.Function<String, Product> load, List<Problem> out) {
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
                if (p.variants().size() < 2 || ChatService.priceByOption(asMaps(p)).isEmpty()) continue;   // no option changes the price
                checkSegment(answer, l, p, related(p, l.substring(s[0], s[1]), shop, load), Integer.parseInt(seg[1]), Integer.parseInt(seg[2]), out);
            }
        }
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
        return a.equals(b) || (a.length() == b.length() + 1 && (a.charAt(0) == 'و' || a.charAt(0) == 'ب') && a.endsWith(b));
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

    private static void checkSegment(String answer, String l, Product p, List<Product> alsoMeant, int from, int to, List<Problem> out) {
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
            String ahead = (seen.isEmpty() || priced) ? valueAhead(toks, i + 1, values) : null;
            if (ahead == null && seen.isEmpty()) continue;
            Set<String> context = ahead != null ? Set.of(ahead) : new LinkedHashSet<>(seen);
            if (ahead != null) aheadTaken = ahead;
            priced = true;
            double amount = Double.parseDouble(t.word());
            List<Variant> fit = p.variants().stream().filter(x -> x.options().values().containsAll(context)).toList();
            if (fit.isEmpty()) continue;
            Set<Double> ok = new java.util.HashSet<>();
            for (Variant x : fit) { ok.add(x.price()); if (x.before() > 0) ok.add(x.before()); for (int k = 2; k <= 10; k++) ok.add(x.price() * k); }
            for (Product r : alsoMeant) for (Variant x : r.variants()) { ok.add(x.price()); if (x.before() > 0) ok.add(x.before()); }
            if (ok.contains(amount)) continue;
            Set<Double> real = new java.util.TreeSet<>();
            for (Variant x : fit) real.add(x.price());
            if (real.size() != 1) continue;   // the values said do not pin one price: nothing exact to say
            double right = real.iterator().next();
            String what = "«" + p.name() + "» " + String.join(" ", context);
            String fixed = answer.substring(0, t.start()) + num(right, arabicDigits(answer.substring(t.start(), t.end()))) + answer.substring(t.end());
            out.add(new Problem("OPTION_PRICE", what + " costs " + num(right, false) + ", not " + num(amount, false) + ". Say exactly " + num(right, false) + ".", fixed));
            return;
        }
    }

    private static final Set<String> FILLER_AHEAD = Set.of("جنيه", "ج", "جم", "egp", "le", "pounds", "pound", "ل", "لل", "for", "each", "the", "a");

    /**
     * The option value a price is for when it comes right after it, past «جنيه» or «لـ» («90 جنيه لـ٣ قطع», «70 جنيه (٦ قطع)»), or null. With nothing between, the next value starts
     * the next pair («عادي ١٢٥ / كبير ١٦٠»).
     */
    private static String valueAhead(List<Tok> toks, int from, Map<List<String>, String> values) {
        int fillers = 0;
        for (int i = from; i < Math.min(toks.size(), from + 4); i++) {
            Object[] v = valueAt(toks, i, values);
            if (v != null) {
                int after = i + (int) v[1];
                boolean ownPrice = after < toks.size() && NUMBER.matcher(toks.get(after).word()).matches();   // «٨٥ ج (كبير ١١٠)»: that value has its own price
                return fillers > 0 && !ownPrice ? (String) v[0] : null;
            }
            if (!FILLER_AHEAD.contains(toks.get(i).word())) return null;
            fillers++;
        }
        return null;
    }

    // ---- 2. colours and sizes of one product ----------------------------------------------------------------------------

    static final Pattern NEG = Pattern.compile("(?:(?<!\\p{L})مش(?!\\p{L})|مفيش|معندناش|ماعندناش|مبقاش|خلص|خلصان|خلصت|نفذ|نفذت|غير\\s+(?:متاح|متوفر|موجود)|للاسف|ليس|لا\\s+يوجد|not\\s+available|out\\s+of\\s+stock|sold\\s+out|unavailable|don'?t\\s+have|do\\s+not\\s+have|isn'?t|aren'?t|no\\s+longer)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAIL = Pattern.compile("(?:متاح|متاحه|متوفر|متوفره|موجود|موجوده|عندنا|بالوان|الوان|الالوان|المقاسات|مقاسات|available|in\\s+stock|comes?\\s+in|colou?rs|sizes)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_SIZES = Pattern.compile("(?<!\\p{L})(?:ب|في\\s+|ف)?(?:كل|جميع)\\s+(?:ال)?(?:مقاسات|المقاسات|الالوان|الوان)|(?:ال)?(?:مقاسات|الوان)\\s+(?:كلها|كلهم)|all\\s+(?:the\\s+)?(?:sizes|colou?rs)|every\\s+size", Pattern.CASE_INSENSITIVE);
    /** Words that offer the item as ready to sell («تحب أحجزهولك؟», «موجود», "available"). */
    private static final Pattern OFFER = Pattern.compile("(?:احجز|أحجز|نحجز|اجهز|أجهز|نجهز|ابعتهولك|متاح|متاحه|متوفر|متوفره|موجود|موجوده|available|in\\s+stock|shall\\s+i\\s+(?:book|reserve|order))", Pattern.CASE_INSENSITIVE);
    /** A stock count («١٦ قطعة في المخزن», «فاضل ٣ بس», "6 left"); a number that is part of a name or option («وجبة بروستد ٤ قطع») is not one. */
    private static final Pattern COUNT = Pattern.compile("(?:(\\d{1,4})\\s*(?:قطعه|قطع|حته|حتت)\\s*(?:في\\s+المخزن|بالمخزن|متاحه|متاحين|متوفره|متوفرين|موجوده|موجودين|متبقيه|باقيه|فاضله)|(?:فاضل|باقي|متبقي|فيه|في)\\s+(\\d{1,4})\\s*(?:قطعه|قطع|حته|حتت|بس)|(\\d{1,4})\\s*(?:pieces?\\s+)?(?:in\\s+stock|left|available))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SIZE_CODES = Set.of("xs", "s", "m", "l", "xl", "xxl", "3xl", "xxxl");

    private static void optionStock(String customer, String answer, List<Mention> ms, String only, java.util.function.Function<String, Product> load, List<Problem> out) {
        String l = light(answer);
        for (int[] s : sentences(answer)) {
            List<Mention> here = ms.stream().filter(m -> m.start() >= s[0] && m.start() < s[1]).toList();
            boolean carried = here.isEmpty();
            String slug = here.size() == 1 ? here.get(0).slug() : carried ? lastBefore(ms, s[0], only) : null;
            if (slug == null) continue;
            Product p = load.apply(slug);
            if (p.variants().isEmpty()) continue;
            String sentence = l.substring(s[0], s[1]);
            Map<String, String> said = valuesIn(sentence, p);   // value -> option name
            boolean neg = NEG.matcher(sentence).find();
            // a denial is checked only for the product named in the same sentence (a list line under another product may deny something else)
            if (neg && (!carried || only != null)) {
                String problem = deniedButInStock(p, sentence, customer);
                if (problem != null) { out.add(new Problem("OPTION_STOCK", problem, null)); continue; }
            }
            if (!neg) {
                String combo = soldOutCombo(p, sentence, customer, l);
                if (combo != null) { out.add(new Problem("OPTION_STOCK", combo, null)); continue; }
                String count = wrongCount(p, sentence, customer);
                if (count != null) { out.add(new Problem("OPTION_STOCK", count, null)); continue; }
            }
            if (!neg && ALL_SIZES.matcher(sentence).find()) {
                String missing = missingCombos(p, said);
                if (missing != null) { out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» is NOT in every size and colour: " + missing + ". Say exactly which are in stock.", null)); continue; }
            }
            if (!neg && AVAIL.matcher(sentence).find()) {
                Set<String> realSizes = new LinkedHashSet<>();
                Set<String> shownSizes = new LinkedHashSet<>();
                for (Variant v : p.variants()) v.options().forEach((k, val) -> { if (sizeOption(k)) { realSizes.add(bare(light(val)).replace("\u200C", "")); shownSizes.add(val); } });
                if (realSizes.isEmpty()) continue;
                // only the size codes said right after the product's name (another product named later in the sentence has its own sizes)
                int from = carried ? s[0] : here.get(0).end();
                List<Tok> after = tokens(l, from, s[1]);
                List<String> invented = new ArrayList<>();
                for (int i = 0; i < Math.min(after.size(), 14); i++) {
                    Tok t = after.get(i);
                    if (SIZE_CODES.contains(t.word()) && !realSizes.contains(t.word()) && answer.substring(t.start(), t.end()).equals(answer.substring(t.start(), t.end()).toUpperCase(Locale.ROOT)))
                        invented.add(t.word().toUpperCase(Locale.ROOT));
                }
                List<String> soldOut = new ArrayList<>();
                for (var e : said.entrySet()) if (sizeOption(e.getValue()) || colorOption(e.getValue()))
                    if (p.variants().stream().noneMatch(v -> v.available() > 0 && v.options().containsValue(e.getKey()))) soldOut.add(e.getKey());
                if (!invented.isEmpty()) out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» does not come in " + String.join("، ", invented) + "; its sizes are " + String.join("، ", shownSizes) + ". Say only those.", null));
                else if (!soldOut.isEmpty() && said.size() == soldOut.size()) out.add(new Problem("OPTION_STOCK", "«" + p.name() + "» " + String.join(" ", soldOut) + " is SOLD OUT right now: " + stockLine(p, Map.of()) + ".", null));
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
    private static String soldOutCombo(Product p, String sentence, String customer, String reply) {
        if (!OFFER.matcher(reply).find()) return null;   // «متوفرين» may sit in the sentence before the one naming the product
        Map<String, String> want = wanted(p, sentence, customer);
        if (new java.util.HashSet<>(want.values()).size() < 2) return null;   // one option alone is the simpler check below
        List<Variant> exact = p.variants().stream().filter(v -> v.options().values().containsAll(want.keySet())).toList();
        if (exact.isEmpty() || exact.stream().anyMatch(v -> v.available() > 0)) return null;
        if (NEG.matcher(reply).find()) return null;   // the reply says something is not available: the denial checks look at that
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
        String num = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        int said = Integer.parseInt(num), real = exact.get(0).available();
        if (said == real || real <= 0) return null;
        return "«" + p.name() + "» " + String.join(" ", want.keySet()) + " has " + real + " in stock, not " + said + ". Say " + real + " or just that it is available.";
    }

    /**
     * «مش باللون الزيتي»: the clause that denies names values the product HAS in stock together. The denial must sit next to a value (not «٦ قطع مع كاتشب (مش حار)»); a colour or
     * size the product does not come in at all makes the denial right («الأسود مقاس L مش متوفر» for a shirt without black); a size or colour the clause leaves out is taken from the
     * rest of the sentence, then from the customer's message («الذهبي غير متوفر» after «مقاس ٣٨»).
     */
    private static String deniedButInStock(Product p, String sentence, String customer) {
        for (String clause : sentence.split("[،,؛;]")) {
            if (!NEG.matcher(clause).find()) continue;
            Map<String, String> no = valuesIn(clause, p);
            if (no.isEmpty()) continue;
            List<Tok> toks = tokens(clause, 0, clause.length());
            boolean near = false;
            for (int i = 0; i < toks.size() && !near; i++) {
                if (!NEG.matcher(toks.get(i).raw()).matches() && !NEG.matcher(toks.get(i).word()).matches()) continue;
                // «مش متوفر في XXL»: the word after the negation («متوفر») counts as part of it
                int last = i + 1 < toks.size() && AVAIL.matcher(toks.get(i + 1).word()).matches() ? i + 1 : i;
                for (int k = Math.max(0, i - 2); k <= Math.min(toks.size() - 1, last + 2) && !near; k++)
                    for (String val : no.keySet()) for (Tok vt : tokens(light(val), 0, val.length())) if (same(toks.get(k).word(), vt.word())) near = true;
            }
            if (!near) continue;
            Set<String> own = new java.util.HashSet<>();
            for (Variant v : p.variants()) for (String val : v.options().values()) own.add(bare(light(val)).replace("\u200C", ""));
            boolean foreign = false;
            for (Tok t : toks) if ((COLORS.contains(t.word()) || SIZE_CODES.contains(t.word())) && !own.contains(t.word())) foreign = true;
            if (foreign) continue;
            Map<String, String> want = new LinkedHashMap<>(no);
            for (String src : new String[]{sentence, customer == null ? "" : light(customer)}) {
                Map<String, String> more = valuesIn(src, p);
                for (var e : more.entrySet()) if (!want.containsValue(e.getValue())) want.put(e.getKey(), e.getValue());
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
        List<Tok> toks = tokens(lightSentence, 0, lightSentence.length());
        for (Variant v : p.variants())
            for (var e : v.options().entrySet()) {
                if (out.containsKey(e.getValue())) continue;
                List<String> w = new ArrayList<>();
                for (Tok t : tokens(light(e.getValue()), 0, e.getValue().length())) w.add(t.word());
                if (w.isEmpty()) continue;
                for (int i = 0; i + w.size() <= toks.size(); i++) {
                    boolean all = true;
                    for (int k = 0; k < w.size() && all; k++) all = same(toks.get(i + k).word(), w.get(k));
                    if (all) { out.put(e.getValue(), e.getKey()); break; }
                }
            }
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
        by.forEach((c, vals) -> parts.add((c.isEmpty() ? "" : c + ": ") + String.join("، ", vals.stream().filter(x -> !x.isBlank()).distinct().toList())));
        return parts.isEmpty() ? "nothing in stock" : String.join("؛ ", parts);
    }

    /** For «كل المقاسات»: the colour/size pairs that are not in stock, or null when everything is. */
    private static String missingCombos(Product p, Map<String, String> said) {
        Set<String> colors = new LinkedHashSet<>(), sizes = new LinkedHashSet<>();
        for (Variant v : p.variants()) v.options().forEach((k, val) -> { if (colorOption(k)) colors.add(val); else if (sizeOption(k)) sizes.add(val); });
        if (sizes.isEmpty()) return null;
        List<String> saidColors = said.entrySet().stream().filter(e -> colorOption(e.getValue())).map(Map.Entry::getKey).toList();
        String color = saidColors.size() == 1 ? saidColors.get(0) : null;   // several colours listed: every colour is claimed in every size
        List<String> missing = new ArrayList<>();
        for (String c : color != null ? Set.of(color) : colors.isEmpty() ? Set.of("") : colors)
            for (String sz : sizes) {
                boolean ok = p.variants().stream().anyMatch(v -> v.available() > 0 && v.options().containsValue(sz) && (c.isEmpty() || v.options().containsValue(c)));
                if (!ok) missing.add((c.isEmpty() ? "" : c + " ") + sz);
            }
        return missing.isEmpty() ? null : "not in stock: " + String.join("، ", missing) + "; in stock: " + stockLine(p, said);
    }

    // ---- 3. "we do not have it" when we do --------------------------------------------------------------------------------

    private static final Pattern DENY = Pattern.compile("(?:مفيش|معندناش|ماعندناش|محدش|مش\\s+عندنا|مش\\s+موجود|مش\\s+متوفر|مش\\s+متاح|غير\\s+(?:متاح|متوفر|موجود)|مش\\s+في\\s+(?:القائمه|القايمه|المنيو)|مبنبيعش|مش\\s+بنبيع|مش\\s+ببيع|لا\\s+يوجد|لا\\s+نبيع|(?:بس|لكن)\\s+مش|we\\s+(?:don'?t|do\\s+not)\\s+(?:have|sell|carry)|there\\s+(?:is|are)\\s+no|we\\s+have\\s+no)(?!\\p{L})", Pattern.CASE_INSENSITIVE);
    /** Words that say nothing about which product: negations, chat words, time words, vague nouns. */
    private static final Set<String> STOP = Set.of("مش", "مفيش", "معندناش", "ماعندناش", "عندنا", "عندي", "بنبيع", "ببيع", "نبيع", "مبنبيعش", "موجود", "موجوده", "متوفر", "متوفره", "متاح", "متاحه", "يوجد",
            "حاليا", "دلوقتي", "النهارده", "النهاردة", "كامل", "كامله", "خالص", "للاسف", "بس", "لكن", "غير", "ابدا", "تاني", "نوع", "انواع", "اصناف", "صنف", "خيارات", "اختيارات", "موديلات", "موديل", "حاجه", "حاجات",
            "اكل", "منتج", "منتجات", "اي", "ايه", "كده", "كدا", "ده", "دي", "دا", "لوحده", "لوحدها", "فيه", "في", "من", "على", "علي", "عن", "مع", "او", "ولا", "و", "يا", "فندم", "حضرتك", "ان", "انه", "انها",
            "مشكله", "مشاكل", "مشكلة", "specifically", "exactly", "really",
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
                List<String> after = content(sentence.substring(d.end()), 4);
                List<String> beforeKinds = content(sentence.substring(0, d.start()), 6).stream().filter(w -> !AUDIENCE.contains(w)).toList();
                boolean butNot = d.group().startsWith("بس") || d.group().startsWith("لكن");
                List<List<String>> queries = new ArrayList<>();
                if (butNot && !after.isEmpty()) for (String k : beforeKinds) queries.add(List.of(k, after.get(0)));   // «عندنا أحذية للولاد بس مش رجالي» -> أحذية رجالي
                else if (!after.isEmpty()) { queries.add(after); if (after.size() > 1) for (String w : after) if (!AUDIENCE.contains(w)) queries.add(List.of(w)); }
                else for (String k : beforeKinds) queries.add(List.of(k));   // «الثومية ... مش ببيعها لوحدها»
                for (List<String> q : queries) {
                    if (q.isEmpty() || q.stream().anyMatch(w -> w.length() < 3)) continue;
                    List<String> stems = q.stream().map(FactCheck::stem).toList();
                    List<Named> hits = new ArrayList<>(shop.searchAll(stems));
                    if (hits.isEmpty()) continue;
                    hits.sort(java.util.Comparator.comparing((Named h) -> !stems.stream().allMatch(w -> light(h.name()).contains(w))));   // products named for it first
                    List<String> shown = new ArrayList<>();
                    for (Named h : hits.stream().limit(5).toList()) {
                        Product p = shop.product(h.slug());
                        shown.add(h.name() + (p != null && !p.variants().isEmpty() ? " (" + num(p.variants().stream().mapToDouble(Variant::price).min().orElse(0), false) + ")" : ""));
                    }
                    String replacement = arabic(answer) ? "عندنا: " + String.join("، ", shown) + "." : "We have: " + String.join(", ", shown) + ".";
                    out.add(new Problem("FALSE_DENIAL", "You said we do not have «" + String.join(" ", q) + "», but the shop HAS: " + String.join("; ", shown) + ". Never deny it; offer these with their prices.",
                            replaceSentence(answer, s[0], s[1], replacement)));
                    return;
                }
            }
        }
    }

    private static boolean valuesInAny(String lightSentence) {
        for (Tok t : tokens(lightSentence, 0, lightSentence.length())) if (SIZE_CODES.contains(t.word()) || COLORS.contains(t.word())) return true;
        return false;
    }

    private static final Set<String> COLORS = Set.of("اسود", "ابيض", "رمادي", "بيج", "بني", "احمر", "وردي", "برتقالي", "اصفر", "اخضر", "زيتي", "تركواز", "ازرق", "كحلي", "سماوي", "بنفسجي", "ذهبي", "فضي",
            "black", "white", "grey", "gray", "beige", "brown", "red", "pink", "orange", "yellow", "green", "olive", "blue", "navy", "purple", "gold", "silver");

    /** Up to {@code max} words that could name a product (no stop words, no numbers). */
    private static List<String> content(String lightPart, int max) {
        List<String> out = new ArrayList<>();
        for (Tok t : tokens(lightPart, 0, lightPart.length())) {
            String w = t.word();
            if (w.length() < 3 || STOP.contains(w) || w.chars().allMatch(Character::isDigit)) continue;
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
    private static void sale(String customer, String answer, List<Mention> ms, java.util.function.Function<String, Product> load, List<Problem> out) {
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
}

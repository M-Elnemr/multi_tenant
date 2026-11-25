package com.platform.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Structured memory of what a customer told the shop: budget, size, colour likes and dislikes, constraints, name, purchase intent. Facts are read from the customer's own messages by code
 * (no model call), each with the message it came from, a confidence and a freshness; a newer explicit statement about the same thing supersedes the older (the old row stays, marked
 * SUPERSEDED). Retrieval is query-aware: only facts that bear on the customer's current message reach the prompt, labelled as the customer's own words and never as shop data.
 * Nothing here is an authority for a price, stock, variant, order or payment status: those come from the tools. Phone numbers, addresses, e-mails and other identifiers are never stored.
 */
@Component
public class MemoryFacts {
    private static final Logger log = LoggerFactory.getLogger(MemoryFacts.class);

    /** A fact found in one message. {@code confidence} 1.0 = stated outright; lower = a reading of what they said. */
    public record Candidate(String type, String key, String value, double confidence, long ttlDays) {}

    /** A stored fact as retrieved for the prompt. */
    public record Fact(long id, String type, String key, String value, double confidence, String sourceText, Instant at, long sourceMessageId) {}

    private final JdbcClient jdbc;

    public MemoryFacts(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- extraction (pure) ------------------------------------------------------------------------------------------------------

    private static final Map<String, String> COLORS = new LinkedHashMap<>();
    static {
        for (String[] c : new String[][] {
                {"black", "اسود|اسوود|eswed|aswad|black|دارك"}, {"white", "ابيض|abyad|abyd|white"}, {"red", "احمر|ahmar|red"}, {"blue", "ازرق|azra2|azraq|blue"},
                {"green", "اخضر|akhdar|green"}, {"beige", "بيج|beige"}, {"brown", "بني|brown|bonny"}, {"navy", "كحلي|navy|kohly"}, {"grey", "رمادي|gray|grey|ramady"},
                {"pink", "بينك|وردي|pink"}, {"yellow", "اصفر|asfar|yellow"}, {"olive", "زيتي|olive"}, {"orange", "برتقالي|orange"}, {"purple", "موف|بنفسجي|purple"}})
            COLORS.put(c[0], c[1]);
    }
    private static final String LIKE = "(?:بحب|بفضل|افضل|عايز|عاوز|عايزه|عاوزه|محتاج|محتاجه|يعجبني|حابب|حابه|3ayez|3ayz|3ayza|3awz|3awez|3awza|ayez|ba7eb|bahib|b7eb|love|like|want|prefer|need)";
    private static final String AVOID = "(?:الا|ماعدا|عدا|except|besides|مش بحب|مش عايز|مش عاوز|مش عايزه|ما بحبش|مبحبش|بكره|مش حابب|من غير|بدون|mesh 3ayez|mesh 3ayza|mesh 3ayz|mesh 3awez|mesh 3awza|mesh ayez|msh 3ayez|msh 3ayza|msh 3ayz|mish 3ayez|mish 3ayza|mesh bahib|mesh ba7eb|mesh b7eb|msh bahib|mabahebsh|ma bahebsh|ba2rah|bakrah|dont like|do not like|don t like|dont want|do not want|don t want|hate|avoid|no|without)";
    private static final String SMALL_WORDS = "(?:هي|هو|بقت|بقي|بقيت|بقا|اتغيرت|اصبحت|حوالي|تقريبا|about|around|is|now|became|بقى|ل|الي|الى|to|bta3y|bta3i|beta3y|bta3ty)";
    /** «3 الاف», «2k», «1.5 الف», «ك»: the thousands word after a number. */
    private static final String THOUSANDS = "(?:\\s*(الاف|الف|alaf|alf|k|ك)(?![\\p{L}]))?";
    /** «ميزانيتي ٦٠٠», «ميزانيتي بقت ٨٠٠», «ميزانيتي اتغيرت ل ٢٠٠٠», "my budget is now 750", «ميزانيتي 3 الاف», "budget 2k": the budget word, up to two small words, the number, its thousands word. */
    private static final Pattern BUDGET_WORD = Pattern.compile("(ميزانيت\\S*|budget|meezaney\\S*|mizaney\\S*|mezaney\\S*)\\s*(?:" + SMALL_WORDS + "\\s+){0,2}(\\d+(?:[.,]\\d+)?)" + THOUSANDS);
    private static final Pattern BUDGET = Pattern.compile("(في حدود|بحدود|حدود|اقصي سعر|اقصي|مش اكتر من|مش اكثر من|ماكتر من|لحد|حد اقصي|up to|under|max(?:imum)?)\\s*(?:هي|هو|حوالي|تقريبا|about|around|is)?\\s*(\\d+(?:[.,]\\d+)?)" + THOUSANDS);
    /** «ميزانيتي الفين», «ميزانيتي تلات الاف»: the amount said in words. */
    private static final Pattern BUDGET_WORDS = Pattern.compile("(ميزانيت\\S*)\\s*(?:" + SMALL_WORDS + "\\s+){0,2}(الفين|الف|(?:تلات|ثلاث|اربع|خمس|ست|سبع|تمان|ثمان|تسع|عشر)[هة]?\\s+الاف)(?![\\p{L}])");
    private static final Pattern NOT_MORE_THAN = Pattern.compile("(?:^|\\s)(?:مش|ماعدش|مفيش|مش هقدر|معييش|معيش|ماعنديش|معنديش|مش معايا|mesh|msh|mish|not|won t|cant|can t)\\s+(?:\\S+\\s+){0,2}(?:اكتر|اكثر|زياده|aktar|akter|aktr|more|over)\\s+(?:من|عن|men|3an|than)\\s+(\\d+(?:[.,]\\d+)?)" + THOUSANDS);
    private static final Map<String, Integer> WORD_AMOUNT = Map.ofEntries(Map.entry("الفين", 2000), Map.entry("الف", 1000), Map.entry("تلات", 3000), Map.entry("ثلاث", 3000), Map.entry("اربع", 4000), Map.entry("خمس", 5000),
            Map.entry("ست", 6000), Map.entry("سبع", 7000), Map.entry("تمان", 8000), Map.entry("ثمان", 8000), Map.entry("تسع", 9000), Map.entry("عشر", 10000));
    private static final String SZ = "(xxxl|xxl|3xl|2xl|xl|xs|s|m|l|اكس لارج|اكسلارج|ميديم|لارج|سمول|\\d{2})";
    private static final Pattern SIZE = Pattern.compile("(?:^|\\s)و?(مقاسي|مقاس|size|ma2asy|ma2asi|ma2as|maqasy|maqas|بلبس|بلبسه|بلبسها|i wear|i m|im)\\s*(?:هو|is|بتاعي|bta3y|bta3i|beta3y)?\\s*" + SZ + "(?=\\s|$)");
    /** What a size is the size OF: «الجاكيت», «بنطلون», "shoe": one customer wears a different size in each, so each is its own fact («size:jacket»). */
    private static final Map<String, String> ITEMS = new LinkedHashMap<>();
    static {
        for (String[] c : new String[][] {
                {"top", "توب|توبات|top|tops"}, {"shirt", "قميص|قمصان|shirt|shirts|qamis|2amis|amees"}, {"tshirt", "تيشيرت|تيشرت|تيشيرتات|تي شيرت|tshirt|t shirt|tee|tisheert|tishert"}, {"blouse", "بلوزه|بلوزات|blouse|blouses"},
                {"jacket", "جاكيت|جاكتات|jacket|jackets|jaket|jakit|jakeet"}, {"coat", "كوت|معطف|coat"}, {"hoodie", "هودي|hoodie|hoodies"}, {"sweater", "بلوفر|سويتر|sweater|pullover"},
                {"pants", "بنطلون|بنطلونات|بنطال|pants|trousers|pant|bantalon|pantalon|bantaloon"}, {"jeans", "جينز|jeans"}, {"shorts", "شورت|شورتات|shorts|short"}, {"skirt", "تنوره|جيبه|skirt"},
                {"dress", "فستان|فساتين|dress|dresses|fostan|fustan"}, {"shoes", "جزمه|جزم|حذاء|احذيه|كوتشي|كوتشيات|صندل|شبشب|بوت|shoes|shoe|sneakers|sandals|boots|gazma|gezma|kotshy|kotchi"}})
            ITEMS.put(c[0], c[1]);
    }
    private static final Map<String, String> ITEM_OF = new LinkedHashMap<>();
    static { ITEMS.forEach((k, v) -> { for (String w : v.split("\\|")) ITEM_OF.put(w, k); }); }
    private static final String ITEM_RE = "((?:ال|el\\s)?(?:" + String.join("|", ITEM_OF.keySet().stream().sorted((x, y) -> y.length() - x.length()).toList()) + ")(?![\\p{L}\\p{N}]))";
    /** «top size L», «بنطلون مقاسه 32», "shoe size is 42", «جاكيت بلبس L»: the thing first, then the size. */
    private static final Pattern ITEM_SIZE = Pattern.compile("(?:^|\\s)و?" + ITEM_RE + "\\s+(?:مقاسه|مقاس|size|ma2aso|ma2as|بلبس)\\s*(?:هو|is|بتاع\\S*|bta3\\S*)?\\s*" + SZ + "(?=\\s|$)");
    /** «مقاس الجاكيت L», «مقاسي في الجزمة 42», "size of jacket L": the size word, then the thing. */
    private static final Pattern SIZE_ITEM = Pattern.compile("(?:^|\\s)و?(?:مقاسي|مقاس|ma2asy|ma2as|size(?:\\s+of)?)\\s+(?:(?:في|ف|fel|fil|fi)\\s+)?" + ITEM_RE + "\\s*(?:هو|is|بتاع\\S*)?\\s*" + SZ + "(?=\\s|$)");
    /** «مقاسي في الجاكيت L وفي البنطلون 32», "I wear M in shirts and 32 in jeans": the second and later things of one size statement. */
    private static final Pattern ITEM_IN = Pattern.compile("(?:^|\\s)و?(?:في|ف|in|fel|fil|fi)\\s+" + ITEM_RE + "\\s*(?:هو|is|بتاع\\S*)?\\s*" + SZ + "(?=\\s|$)");
    private static final Pattern SIZE_IN_ITEM = Pattern.compile("(?:^|\\s)و?" + SZ + "\\s+(?:في|ف|in|fel|fil|fi)\\s+" + ITEM_RE + "(?=\\s|$)");
    private static final Pattern SIZE_WORD = Pattern.compile("(?:مقاس|size|ma2as|maqas|بلبس|i wear|wear)");
    /** «مقاسي L لا قصدي XL», "size M sorry I mean L": the size said again as a correction replaces the one before it. */
    private static final Pattern CORRECTION = Pattern.compile("(?:^|\\s)(?:قصدي|اقصد|i mean|sorry i mean|actually|بالظبط)\\s+(?:مقاس\\s+|size\\s+)?" + SZ + "(?=\\s|$)");
    /** The size is the customer's only when no «not» stands right before it: «مش مقاسي L», "I don't wear size L". A bare «لا» / "no" opens a correction («لا مقاسي L»), it does not negate. */
    private static final Pattern NEGATOR = Pattern.compile("(?:^|\\s)(?:مش|mesh|msh|mish|مبلبسش|مبقاش|ما\\s*بلبسش|ماليش|ليس|ليست|not|never|dont|don t|doesn t|doesnt|isn t|isnt|aren t|cant|can t|won t|wont|didn t)(?=\\s|$)");
    private static final Pattern DEMONSTRATIVE = Pattern.compile("(?:^|\\s)(?:ده|دي|دا|هذا|هذه|this|that|these|those)(?=\\s|$)");
    /** «النهارده بس», «للهدية», "just for this time": it holds for this purchase only, so it lives a day, not three months. */
    private static final Pattern TEMPORARY = Pattern.compile("(?:النهارده بس|انهارده بس|دلوقتي بس|المره دي بس|المره دى بس|المره دي|لهديه|للهديه|هديه|just for (?:this time|now|today)|for now only|only today|this time only|for a gift|as a gift)");
    /** The size is somebody else's: «مقاس ابني», "for my wife": never the customer's own. */
    private static final Pattern OTHER_PERSON = Pattern.compile("(?:^|\\s)(?:لابني|لبنتي|لجوزي|لمراتي|لاختي|لاخويا|لوالدي|لامي|لصاحبي|لصاحبتي|ابني|بنتي|جوزي|مراتي|اختي|اخويا|for my (?:son|daughter|wife|husband|sister|brother|friend|mother|father|kid|kids)|for him|for her)(?=\\s|$)");
    private static final Pattern INTENT = Pattern.compile("(هاخده|هاخدها|هاخدهم|هشتريه|هشتريها|خلاص عايز اطلب|عايز اطلب|عاوز اطلب|يلا اطلب|i will take it|i ll take it|ill take it|lets order)");
    private static final Pattern CONSTRAINT = Pattern.compile("(مش عايزه?|مش عاوزه?|مش بحب|ما بحبش|مبحبش|من غير|بدون|dont want|do not want|without)\\s+((?:\\S+\\s*){1,4})");
    private static final Pattern RECALL = Pattern.compile("(فاكر|فاكره|تفتكر|افتكر|قلتلك|قلت لك|كنت قلت|اللي قلته|remember|i told you|i said|earlier|as i said|before)");

    static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '٠' && c <= '٩' ? (char) ('0' + (c - '٠')) : c >= '۰' && c <= '۹' ? (char) ('0' + (c - '۰')) : c);
        return b.toString();
    }

    static String normalize(String text) { return FastAnswers.norm(digits(text == null ? "" : text)); }

    private static String sizeValue(String raw) {
        return switch (raw) { case "اكس لارج", "اكسلارج", "xl" -> "XL"; case "ميديم" -> "M"; case "لارج" -> "L"; case "سمول" -> "S"; case "3xl" -> "XXXL"; case "2xl" -> "XXL";
            default -> raw.toUpperCase(Locale.ROOT); };
    }

    private static final Map<String, Pattern> COLOR_PATTERNS = new LinkedHashMap<>();
    static { for (var c : COLORS.entrySet()) COLOR_PATTERNS.put(c.getKey(), Pattern.compile("(^|\\s)(?:و|wel\\s+)?((?:" + AVOID + "|" + LIKE + ")\\s+(?:\\S+\\s+){0,2}?)?(?:و|wel\\s+)?(?:ال|el\\s+)?(?:" + c.getValue() + ")(?=\\s|$)")); }
    private static final Pattern AVOID_P = Pattern.compile("(?:^|\\s)" + AVOID + "(?=\\s|$)");
    private static final Pattern IN_ITEM = Pattern.compile("^\\s*(?:في|ف|على|علي|ع|in|on|for|fel|fil|fi)\\s+" + ITEM_RE + "(?=\\s|$)");
    private static final Pattern INTENT_DOUBT = Pattern.compile("(?:مش متاكد|مش عارف|ولا لا|ولا ايه|ولا لأ|لسه بفكر|يمكن|or not|ممكن اشوف|not sure|maybe|i don t know|let me think)");
    private static final Pattern BARE_NAME = Pattern.compile("^\\s*(?:انا|أنا)\\s+(\\p{IsArabic}{2,15})\\s*$");
    private static final Set<String> NOT_A_NAME = Set.of("عايز", "عاوز", "عايزه", "عاوزه", "محتاج", "محتاجه", "مش", "جاي", "جايه", "هنا", "تمام", "كويس", "كويسه", "بخير", "تعبان", "تعبانه", "زهقان", "زهقانه", "مبسوط", "مبسوطه",
            "موجود", "موجوده", "مستعجل", "مستعجله", "جعان", "جعانه", "عميل", "زبون", "جديد", "جديده", "لسه", "برضه", "بردو", "بس", "كمان", "ده", "دي", "حابب", "حابه", "فاكر", "فاكره", "عارف", "عارفه", "اللي", "الي", "طالب", "طالبه", "اوردر");
    /** At most this much of one sentence and this many sentences are read: a very long message cannot make extraction slow. */
    public static final int MAX_LINE = 2000, MAX_CHUNKS = 3000, MAX_STORED_PER_MESSAGE = 40;

    private static String amount(String num, String thousands) {
        double v;
        try { v = Double.parseDouble(num.replace(',', '.')); } catch (NumberFormatException e) { return null; }
        if (thousands != null) v *= 1000;
        if (v < 10 || v > 10_000_000 || num.replaceAll("\\D", "").length() >= 9) return null;   // not a budget: a short number, or a phone number
        String t = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return t;
    }

    /** The last few words before a position: what could negate or scope what follows. */
    private static String before(String n, int at) { return n.substring(Math.max(0, at - 28), at); }

    private static boolean negatedBefore(String n, int at) {
        String w = before(n, at);
        int cut = Math.max(w.lastIndexOf(" و "), 0);
        return NEGATOR.matcher(w.substring(cut)).find();
    }

    /** A size found in the text: its key («size» or «size:jacket»), value, whether it is negated, where it ends. */
    private record SizeHit(String key, String value, boolean negated, int start, int end) {}

    private static final Pattern PERSONAL = Pattern.compile("(?:^|\\s)و?(?:مقاسي|بلبس\\S*|i wear|wear|i m|im)(?=\\s|$)");
    private static final Pattern ASKING = Pattern.compile("^(?:هل|عندكم|عندك|فيه|متاح|متوفر|do you|have you|is there|are there|can i|ينفع)(?=\\s)");

    private static List<SizeHit> sizeHits(String n) { return sizeHits(n, false); }

    private static List<SizeHit> sizeHits(String n, boolean asked) {
        List<SizeHit> hits = new ArrayList<>();
        // «عندكم بنطلون جينز مقاس 32؟»: a question about stock, not a statement of their size (unless it says it is theirs)
        if ((asked || ASKING.matcher(n).find()) && !PERSONAL.matcher(n).find()) return hits;
        for (Pattern ip : new Pattern[] {ITEM_SIZE, SIZE_ITEM}) {
            Matcher m = ip.matcher(n);
            while (m.find()) {
                String item = m.group(1).replaceFirst("^(?:ال|el\\s)", "");
                String cat = ITEM_OF.get(item);
                if (cat == null) cat = ITEM_OF.get(m.group(1));
                if (cat == null) continue;
                String raw = m.group(2);
                if (raw.matches("\\d{2}") && (Integer.parseInt(raw) < 20 || Integer.parseInt(raw) > 60)) continue;
                int st = m.start(1);
                if (OTHER_PERSON.matcher(before(n, st)).find()) continue;   // somebody else's
                hits.add(new SizeHit("size:" + cat, sizeValue(raw), negatedBefore(n, st), m.start(), m.end()));
            }
        }
        for (Pattern ip : new Pattern[] {ITEM_IN, SIZE_IN_ITEM}) {   // the later things of a size statement: only after a size word in the same sentence
            Matcher m = ip.matcher(n);
            while (m.find()) {
                boolean sizeFirst = ip == SIZE_IN_ITEM;
                String rawSize = m.group(sizeFirst ? 1 : 2), item = m.group(sizeFirst ? 2 : 1).replaceFirst("^(?:ال|el\\s)", "");
                String cat = ITEM_OF.get(item) != null ? ITEM_OF.get(item) : ITEM_OF.get(m.group(sizeFirst ? 2 : 1));
                int st = m.start();
                if (cat == null || !SIZE_WORD.matcher(n.substring(0, st)).find()) continue;
                if (hits.stream().anyMatch(h -> h.start() < m.end() && st < h.end())) continue;
                if (rawSize.matches("\\d{2}") && (Integer.parseInt(rawSize) < 20 || Integer.parseInt(rawSize) > 60)) continue;
                hits.add(new SizeHit("size:" + cat, sizeValue(rawSize), negatedBefore(n, st), st, m.end()));
            }
        }
        Matcher s = SIZE.matcher(n);
        while (s.find()) {
            int st = s.start(), en = s.end();
            if (hits.stream().anyMatch(h -> h.start() < en && st < h.end())) continue;   // already read as the size of a named thing
            String raw = s.group(2);
            if (raw.matches("\\d{2}") && (Integer.parseInt(raw) < 20 || Integer.parseInt(raw) > 60)) continue;   // a shoe or trouser size, not a stray number
            String kw = s.group(1);
            boolean personal = kw.equals("مقاسي") || kw.startsWith("بلبس") || kw.startsWith("i ") || kw.equals("im");
            if (!personal && DEMONSTRATIVE.matcher(n.substring(en, Math.min(n.length(), en + 30))).find()) continue;   // «مقاس L ده ضيق»: the size of that product
            if (OTHER_PERSON.matcher(before(n, st)).find() || OTHER_PERSON.matcher(n.substring(en, Math.min(n.length(), en + 14))).find()) continue;
            hits.add(new SizeHit("size", sizeValue(raw), negatedBefore(n, st), st, en));
        }
        Matcher fix = CORRECTION.matcher(n);   // a correction replaces the last size said before it
        while (fix.find() && !hits.isEmpty()) {
            SizeHit last = null;
            for (SizeHit h : hits) if (h.end() <= fix.start() + 1 && (last == null || h.end() > last.end())) last = h;
            if (last == null || last.negated()) continue;
            hits.remove(last);
            hits.add(new SizeHit(last.key(), sizeValue(fix.group(1)), false, last.start(), fix.end()));
        }
        return hits;
    }

    /** Sizes the customer says are NOT theirs («مش مقاسي L», "I don't wear size L"). Never stored as a size; the memory uses them to retire a wrong size it still holds. */
    public static List<Candidate> negatedSizes(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (SizeHit h : sizeHits(normalize(clip(text)), text.contains("؟") || text.contains("?"))) if (h.negated()) out.add(new Candidate("SIZE", h.key(), h.value(), 1.0, 90));
        return out;
    }

    /** «1.5 الف», "2.5k": the dot would be read as a break between two numbers, so a decimal before a thousands word becomes the whole number («1500»). */
    private static final Pattern DECIMAL_THOUSANDS = Pattern.compile("(\\d+)[.,٫](\\d{1,2})\\s*(?:الاف|آلاف|الف|ألف|k|ك)(?![\\p{L}])");
    private static final Pattern GROUPED = Pattern.compile("(?<=\\d)[,٬](?=\\d{3}(?!\\d))");
    static String decimalThousands(String text) {
        Matcher m = DECIMAL_THOUSANDS.matcher(GROUPED.matcher(digits(text)).replaceAll(""));   // «5,000» is five thousand, not 5 and 000
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + (m.group(2) + "00").substring(0, 3) + " "));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String clip(String text) { return text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text; }

    /**
     * The sentences of a message (a line break or . ! ? ؟ ends one), each a unit of its own: a negation, a «this one» or a «just for now» only reaches the sentence it is in, a source snippet is the
     * sentence the fact came from, and a very long message loses nothing (its budget may be in the last line). At most {@code MAX_CHUNKS} sentences are read.
     */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\n"))
            for (String sentence : line.split("(?<=[.!?؟])\\s+")) {
                if (sentence.isBlank()) continue;
                if (out.size() >= MAX_CHUNKS) return out;
                out.add(clip(sentence.strip()));
            }
        return out;
    }

    /** The facts a message states, in the order they appear. Pure: no database. Sensitive identifiers are never part of a fact. */
    public static List<Candidate> extract(String text) {
        List<Candidate> out = new ArrayList<>();
        for (String chunk : chunks(text)) out.addAll(extractOne(chunk));
        return out;
    }

    /** Negated sizes of a whole message. */
    public static List<Candidate> negatedSizesIn(String text) {
        List<Candidate> out = new ArrayList<>();
        for (String chunk : chunks(text)) out.addAll(negatedSizes(chunk));
        return out;
    }

    static List<Candidate> extractOne(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String raw = clip(text);
        String n = normalize(decimalThousands(raw));
        boolean temporary = TEMPORARY.matcher(n).find();
        for (Pattern bp : new Pattern[] {BUDGET_WORD, BUDGET}) {
            Matcher b = bp.matcher(n);
            while (b.find()) {
                String value = amount(b.group(2), b.group(3));
                if (value == null) continue;
                if (out.stream().anyMatch(c -> c.type().equals("BUDGET") && c.value().equals(value))) continue;   // the same statement matched by both patterns
                out.add(new Candidate("BUDGET", "budget", value, bp == BUDGET_WORD ? 1.0 : 0.75, 90));
            }
        }
        Matcher cap = NOT_MORE_THAN.matcher(n);   // «مش هصرف اكتر من ١٠٠٠»
        while (cap.find()) {
            String value = amount(cap.group(1), cap.group(2));
            if (value != null && out.stream().noneMatch(c -> c.type().equals("BUDGET") && c.value().equals(value))) out.add(new Candidate("BUDGET", "budget", value, 0.75, 90));
        }
        Matcher bw = BUDGET_WORDS.matcher(n);
        while (bw.find()) {
            String w = bw.group(2).trim().split("\\s+")[0].replaceAll("[هة]$", "");
            Integer v = WORD_AMOUNT.get(w);
            if (v == null || out.stream().anyMatch(c -> c.type().equals("BUDGET"))) continue;
            out.add(new Candidate("BUDGET", "budget", String.valueOf(v), 1.0, 90));
        }
        for (SizeHit h : sizeHits(n, raw.contains("؟") || raw.contains("?"))) if (!h.negated()) out.add(new Candidate("SIZE", h.key(), h.value(), 1.0, 90));
        for (var c : COLOR_PATTERNS.entrySet()) {
            Matcher m = c.getValue().matcher(n);
            while (m.find()) {
                String lead = m.group(2);
                if (lead == null) continue;   // a colour merely mentioned says nothing about what they want
                String after = n.substring(m.end(), Math.min(n.length(), m.end() + 25));
                if (DEMONSTRATIVE.matcher(after).find() || IN_ITEM.matcher(after).find()) continue;   // about that product, or about one kind of thing («في الجاكيت»), not their taste in general   // «مش بحب الاسود في الجاكيت ده»: about that product, not their taste
                boolean avoid = AVOID_P.matcher(lead).find();
                out.add(new Candidate("COLOR", "color:" + c.getKey(), avoid ? "avoid" : "like", 0.9, temporary ? 1 : 90));
            }
        }
        for (var c : COLOR_PATTERNS.keySet()) {   // «جاكيت اسود مش ابيض»: a colour after «مش» when another colour came first is the one they do not want
            Matcher m = Pattern.compile("(?:^|\\s)مش\\s+(?:ال)?(?:" + COLORS.get(c) + ")(?=\\s|$)").matcher(n);
            if (!m.find() || out.stream().anyMatch(x -> x.key().equals("color:" + c))) continue;
            String earlier = n.substring(0, m.start());
            if (COLORS.entrySet().stream().anyMatch(o -> !o.getKey().equals(c) && Pattern.compile("(?:^|\\s)و?(?:ال)?(?:" + o.getValue() + ")(?=\\s|$)").matcher(earlier).find()))
                out.add(new Candidate("COLOR", "color:" + c, "avoid", 0.8, temporary ? 1 : 90));
        }
        // the words of a condition keep the customer's own spelling when they can be matched one to one
        String[] surface = digits(raw).replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+"), folded = n.isEmpty() ? new String[0] : n.split("\\s+");
        boolean sameWords = surface.length == folded.length;
        Matcher k = CONSTRAINT.matcher(n);
        while (k.find()) {
            String what = k.group(2).trim();
            if (what.isEmpty() || COLORS.values().stream().anyMatch(re -> Pattern.compile("^(?:ال)?(?:" + re + ")\\b").matcher(what).find())) continue;   // colours are handled above
            if (what.matches(".*\\d{6,}.*") || what.contains("@")) continue;   // never store an identifier
            String[] w = what.split("\\s+");
            if (sameWords) {   // find where this match's words sit among all the words, take the customer's spelling
                int from = n.substring(0, k.start(2)).isEmpty() ? 0 : n.substring(0, k.start(2)).trim().split("\\s+").length;
                if (from + w.length <= surface.length) { String[] sw = java.util.Arrays.copyOfRange(surface, from, from + w.length); boolean ok = true; for (int i = 0; i < w.length; i++) ok &= normalize(sw[i]).equals(w[i]); if (ok) w = sw; }
            }
            String value = String.join(" ", java.util.Arrays.copyOf(w, Math.min(w.length, 4)));
            out.add(new Candidate("CONSTRAINT", "constraint:" + w[0], (k.group(1).startsWith("بدون") || k.group(1).startsWith("من غير") || k.group(1).startsWith("without") ? "without " : "not ") + value, 0.85, temporary ? 1 : 90));
        }
        String name = ChatService.statedName(raw);
        double nameConf = 1.0;
        if (name == null || name.isBlank()) {   // a message that is only «انا احمد»
            Matcher bn = BARE_NAME.matcher(raw);
            if (bn.find() && !NOT_A_NAME.contains(normalize(bn.group(1)))) { name = bn.group(1); nameConf = 0.7; }
        }
        else if (BARE_NAME.matcher(raw).find()) nameConf = 0.7;   // only «انا احمد», nothing else: a name most likely, still a reading (the reply side treats it as known)
        if (name != null && !name.isBlank()) out.add(new Candidate("NAME", "name", name, nameConf, 365));
        Matcher im = INTENT.matcher(n);
        if (im.find() && !negatedBefore(n, im.start()) && !INTENT_DOUBT.matcher(n).find() && !raw.matches("(?s).*(?:هاخد\\S*|هشتري\\S*|take it)\\s*[؟?].*"))
            out.add(new Candidate("INTENT", "intent:purchase", "wants to order", 0.6, 3));   // a reading, not a confirmation: shown as inferred
        return out;
    }

    // ---- storage ------------------------------------------------------------------------------------------------------------------

    /**
     * Reads the facts out of a customer message (line by line, so a later line can correct an earlier one) and stores them for this conversation. The same fact again changes nothing; a
     * different value for the same thing supersedes the older one. Never throws: memory is not allowed to fail a reply.
     */
    public int record(UUID tenantId, UUID conversationId, long messageId, String text) {
        int stored = 0;
        try {
            if (text == null) return 0;
            Map<String, String> last = new LinkedHashMap<>();   // the value last handled per key in THIS message: a repeat (a paste that says the same thing 500 times) is not looked up or written again
            for (String sentence : chunks(text)) {
                for (Candidate c : extractOne(sentence)) {
                    if (c.value().equals(last.get(c.key()))) continue;
                    if (stored >= MAX_STORED_PER_MESSAGE) break;   // one message cannot flood the table
                    last.put(c.key(), c.value());
                    if (store(tenantId, conversationId, messageId, sentence, c)) stored++;
                }
                for (Candidate c : negatedSizes(sentence)) retire(tenantId, conversationId, c);
            }
        } catch (RuntimeException e) { log.warn("Memory facts not stored: {}", e.toString()); }
        return stored;
    }

    /** «مش مقاسي L»: a size the customer still has on file as theirs and now says is not: it stops being active (the row stays, marked SUPERSEDED, with no replacement). */
    private void retire(UUID tenantId, UUID conv, Candidate c) {
        jdbc.sql("UPDATE chat.memory_facts SET status = 'SUPERSEDED' WHERE tenant_id = :t AND conversation_id = :c AND type = 'SIZE' AND fact_key = :k AND value = :v AND status = 'ACTIVE'")
                .param("t", tenantId).param("c", conv).param("k", c.key()).param("v", c.value()).update();
    }

    private boolean store(UUID tenantId, UUID conv, long messageId, String line, Candidate c) {
        var old = jdbc.sql("SELECT id, value, confidence, expires_at < now() AS expired FROM chat.memory_facts WHERE tenant_id = :t AND conversation_id = :c AND fact_key = :k AND status = 'ACTIVE'")
                .param("t", tenantId).param("c", conv).param("k", c.key()).query().listOfRows().stream().findFirst().orElse(null);
        if (old != null && !Boolean.TRUE.equals(old.get("expired")) && c.value().equals(old.get("value"))) return false;   // repeated information
        if (old != null && !Boolean.TRUE.equals(old.get("expired")) && c.confidence() < ((Number) old.get("confidence")).doubleValue() - 0.2 && "INTENT".equals(c.type())) return false;   // a weak reading never replaces a firm fact
        String snippet = MessageReplies.clip(line.strip().replaceAll("\\s+", " "), 240);
        long id = jdbc.sql("""
                INSERT INTO chat.memory_facts (tenant_id, conversation_id, type, fact_key, value, source_message_id, source_text, confidence, expires_at)
                VALUES (:t, :c, :ty, :k, :v, :m, :s, :cf, now() + make_interval(days => :d))
                ON CONFLICT DO NOTHING RETURNING id
                """).param("t", tenantId).param("c", conv).param("ty", c.type()).param("k", c.key()).param("v", c.value()).param("m", messageId).param("s", snippet).param("cf", (float) c.confidence()).param("d", (int) c.ttlDays())
                .query(Long.class).optional().orElse(-1L);
        if (old != null) {
            jdbc.sql("UPDATE chat.memory_facts SET status = CASE WHEN expires_at < now() THEN 'EXPIRED' ELSE 'SUPERSEDED' END, superseded_by = NULLIF(:n, -1) WHERE id = :o").param("n", id).param("o", ((Number) old.get("id")).longValue()).update();
            if (id < 0) {   // the unique index refused the new row while the old one was still active: retry now that the old one is out of the way
                id = jdbc.sql("""
                        INSERT INTO chat.memory_facts (tenant_id, conversation_id, type, fact_key, value, source_message_id, source_text, confidence, expires_at)
                        VALUES (:t, :c, :ty, :k, :v, :m, :s, :cf, now() + make_interval(days => :d)) RETURNING id
                        """).param("t", tenantId).param("c", conv).param("ty", c.type()).param("k", c.key()).param("v", c.value()).param("m", messageId).param("s", snippet).param("cf", (float) c.confidence()).param("d", (int) c.ttlDays())
                        .query(Long.class).single();
                jdbc.sql("UPDATE chat.memory_facts SET superseded_by = :n WHERE id = :o").param("n", id).param("o", ((Number) old.get("id")).longValue()).update();
            }
        }
        return true;
    }

    // ---- query-aware retrieval -----------------------------------------------------------------------------------------------------

    private static final Map<String, List<String>> TRIGGERS = Map.of(
            "BUDGET", List.of("ميزانيه", "سعر", "بكام", "كام", "غالي", "غاليه", "ارخص", "رخيص", "تكلف", "خصم", "عرض", "budget", "price", "cheap", "cheaper", "expensive", "cost", "afford", "كده كام", "اوفر", "تمن"),
            "SIZE", List.of("مقاس", "مقاسي", "size", "يناسب", "ضيق", "واسع", "لارج", "ميديم", "سمول", "fit", "fits", "يلبس", "بلبس"),
            "COLOR", List.of("لون", "الوان", "color", "colour", "colors", "اسود", "ابيض", "احمر", "ازرق", "اخضر", "بيج", "بني", "كحلي", "رمادي", "غامق", "فاتح"),
            "CONSTRAINT", List.of("رشح", "اقترح", "ايه", "عندكم", "عايز", "محتاج", "recommend", "suggest", "show", "have", "looking", "اختار", "بديل", "غير"),
            "INTENT", List.of("اطلب", "اشتري", "اوردر", "order", "buy", "تمام", "خلاص", "اكمل", "كمل"),
            "NAME", List.of("اسمي", "ناديني", "اسمى", "my name", "call me"));

    /**
     * The facts that bear on this message, best first (at most {@code limit}). A message that asks the assistant to recall («فاكر؟», «قلتلك», "I told you") gets every active fact; any other
     * message gets only the facts whose subject it touches (a price question touches the budget, a colour word the colour preferences) or whose own words it shares. Nothing relevant: nothing.
     */
    public List<Fact> relevant(UUID tenantId, UUID conversationId, String message, int limit) { return relevant(tenantId, conversationId, message, limit, Long.MAX_VALUE); }

    /**
     * Same, but only facts whose source message is OLDER than {@code beforeMessageId} (the oldest message the model is given as history): what was said inside that window is current
     * conversation, already in front of the model; this memory is the historical part, so the two are not mixed or repeated.
     */
    public List<Fact> relevant(UUID tenantId, UUID conversationId, String message, int limit, long beforeMessageId) {
        String n = normalize(message);
        List<Fact> all = active(tenantId, conversationId).stream().filter(f -> f.sourceMessageId() < beforeMessageId).toList();
        if (all.isEmpty() || n.isBlank()) return List.of();
        boolean recall = RECALL.matcher(n).find();
        Set<String> tokens = new LinkedHashSet<>(List.of(n.split("\\s+")));
        List<Map.Entry<Fact, Double>> scored = new ArrayList<>();
        for (Fact f : all) {
            double score = 0;
            for (String t : TRIGGERS.getOrDefault(f.type(), List.of())) if (n.contains(t)) { score += 2; break; }
            String own = normalize(f.value() + " " + f.key().replace("color:", "").replace("constraint:", ""));
            for (String w : own.split("\\s+")) if (w.length() >= 3 && tokens.contains(w)) score += 3;
            if (f.type().equals("COLOR") && COLORS.get(f.key().substring(6)) != null && Pattern.compile("(?:^|\\s)(?:ال)?(?:" + COLORS.get(f.key().substring(6)) + ")(?=\\s|$)").matcher(n).find()) score += 4;   // the very colour they ask about
            if (f.type().equals("SIZE") && f.key().startsWith("size:")) {   // a size of one thing («size:jacket») bears on a message about that thing
                String item = ITEMS.get(f.key().substring(5));
                if (item != null && Pattern.compile("(?:^|\\s)(?:ال)?(?:" + item + ")(?=\\s|$)").matcher(n).find()) score += 4;
            }
            if (recall) score += 5;
            if (score > 0) scored.add(Map.entry(f, score));
        }
        scored.sort(Map.Entry.<Fact, Double>comparingByValue().reversed().thenComparing(e -> e.getKey().at(), Comparator.reverseOrder()));
        List<Fact> out = new ArrayList<>();
        for (var e : scored) if (out.size() < (recall ? Math.max(limit, 8) : limit)) out.add(e.getKey());
        return out;
    }

    /** The active, unexpired facts of one conversation of one shop (never another's), newest first. */
    public List<Fact> active(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id, type, fact_key, value, confidence, source_text, created_at, coalesce(source_message_id, 0) AS src FROM chat.memory_facts
                WHERE tenant_id = :t AND conversation_id = :c AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > now()) ORDER BY created_at DESC, id DESC
                """).param("t", tenantId).param("c", conversationId).query((rs, i) -> new Fact(rs.getLong("id"), rs.getString("type"), rs.getString("fact_key"), rs.getString("value"), rs.getDouble("confidence"),
                rs.getString("source_text"), rs.getTimestamp("created_at").toInstant(), rs.getLong("src"))).list();
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Africa/Cairo"));

    /** The prompt section for these facts, or "" when there are none. Provenance (what they said and when) is shown; uncertain facts are marked as inferred. */
    public static String block(List<Fact> facts) {
        if (facts == null || facts.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\nWHAT THE CUSTOMER TOLD US EARLIER (their own words, picked out by code because they bear on this message; this is NOT shop data and never a price, stock, variant, order or payment status: those come only from the tools). "
                + "A newer statement of theirs in the conversation above beats anything here.\n");
        for (Fact f : facts) {
            String label = switch (f.type()) {
                case "BUDGET" -> "Their budget: " + f.value() + " (what they want to spend, NOT a product's price)";
                case "SIZE" -> f.key().startsWith("size:") ? "Their size in " + f.key().substring(5) + ": " + f.value() : "Their size: " + f.value();
                case "COLOR" -> ("avoid".equals(f.value()) ? "Colour they do NOT want: " : "Colour they like: ") + f.key().substring(6);
                case "CONSTRAINT" -> "A condition they set: " + f.value();
                case "NAME" -> "Their name: " + f.value();
                default -> "Their purchase intent: " + f.value();
            };
            b.append("  - ").append(label).append(f.confidence() < 0.8 ? " [inferred, not confirmed]" : "").append("  — said on ").append(DAY.format(f.at())).append(": «").append(f.sourceText() == null ? "" : f.sourceText()).append("»\n");
        }
        return b.toString();
    }
}

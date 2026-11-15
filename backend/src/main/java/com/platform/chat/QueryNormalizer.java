package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a customer types is not what a catalog says: «3ayez jacket shitwy» for «جاكيت شتوي», «جاكيييت» for «جاكيت», «بنطلون» for «بنطال». This turns a query into groups of word variants (the word
 * itself, its Arabic spelling normalised, its Arabic equivalent when it is common retail vocabulary written in Latin letters or English) that a lexical match then tries in turn, with a one-letter
 * typo tolerance on longer words. Deterministic, no model, no network; the vocabulary is small and generic on purpose (a long list would only fit one test set).
 */
final class QueryNormalizer {
    private QueryNormalizer() {}

    /** Latin-letter and English retail words -> the Arabic words catalogs use (several spellings each). */
    private static final Map<String, List<String>> LATIN = new LinkedHashMap<>();
    static {
        String[][] pairs = {
                {"jacket|jaket|jakit|jaket", "جاكيت|جاكت"}, {"coat|palto|balto", "بالطو|معطف"}, {"shirt|qamis|2amis|ameis", "قميص"}, {"tshirt|t-shirt|tee|teeshirt|tishirt", "تيشيرت"},
                {"pants|pantalon|bantalon|trousers", "بنطلون|بنطال"}, {"jeans|jean", "جينز"}, {"dress|fostan|fustan", "فستان"}, {"skirt|jonella|jibba", "جيبة"}, {"shoes|shoe|gazma|gezma", "جزمة|حذاء"},
                {"sneakers|kotshi|kutshi", "كوتشي|حذاء رياضي"}, {"bag|shanta|shanta|shunta", "شنطة|حقيبة"}, {"wallet|mahfaza|mo7faza", "محفظة"}, {"watch|sa3a|saa3a", "ساعة"},
                {"headphones|headphone|earphones|sama3a|sama3at", "سماعة|سماعات"}, {"phone|mobile|mobail|telephone", "موبايل|هاتف"}, {"laptop|labtop", "لابتوب"}, {"charger|shaher|sha7en", "شاحن"},
                {"cotton|qotn|cottn|kotn", "قطن"}, {"wool|soof|souf", "صوف"}, {"leather|geld|jeld|gild", "جلد"}, {"linen|kettan", "كتان"},
                {"black|eswed|aswad|eswad", "اسود"}, {"white|abyad|abyd|abiad", "ابيض"}, {"red|ahmar|a7mar", "احمر"}, {"blue|azra2|azraq|azrak", "ازرق"}, {"green|akhdar|a5dar", "اخضر"},
                {"beige|beij", "بيج"}, {"brown|bonny|boni", "بني"}, {"navy|kohly|kuhli", "كحلي"}, {"grey|gray|ramady|ramadi", "رمادي"}, {"pink|wardy", "بينك|وردي"}, {"yellow|asfar", "اصفر"},
                {"winter|shitwy|shatawy|shetwy|shitwi", "شتوي"}, {"summer|sayfy|sayfi|seify", "صيفي"}, {"size|maqas|ma2as|me2as|maqaas", "مقاس"}, {"price|be kam|bekam|bkam|ad eh", "بكام|سعر"},
                {"cheap|rakhis|ra5is", "رخيص"}, {"big|kebir|kbeer|kbir", "كبير"}, {"small|so3ayar|soghayar", "صغير"}, {"kids|kid|atfal|children", "اطفال"}, {"men|rigaly|ragaly|rejaly", "رجالي"},
                {"women|hareemy|harimy|hareemi|hremy", "حريمي"}, {"wash|ghasil|ghaseel|3asil", "غسيل"}, {"shrink|yenkamesh|yenkamish|yenkamash", "ينكمش"}, {"waterproof|water proof", "مقاوم للماء|ضد الماء"},
                {"3ayez|3ayz|3awz|3awez|ayez|3ayza|3awza", "عايز|عاوز"}, {"mesh|msh", "مش"}, {"fi|fee", "في"}, {"da|dah|deh|dih", "ده|دي"}};
        for (String[] p : pairs) {
            List<String> ar = List.of(p[1].split("\\|"));
            for (String lat : p[0].split("\\|")) if (!lat.isBlank()) LATIN.put(lat.toLowerCase(java.util.Locale.ROOT), ar);
        }
    }

    /** The word with letters repeated for emphasis squeezed («جاكيييت» -> «جاكيت») and Arabic letters unified (the same normalisation the rest of the search uses). */
    static String squeeze(String w) { return FastAnswers.norm(w).replaceAll("(\\p{L})\\1{2,}", "$1$1").replaceAll("(\\p{L})\\1+(?=\\p{L})", "$1"); }

    /**
     * Words a customer uses to ASK, not to name what they want («عايز لابتوب»: «عايز» is not in any catalog): matching them as required words made a request like «عايز موبايل» find nothing.
     * Request verbs, politeness, question words and English request phrases only; no product, colour, material or size word is in this list (generic on purpose).
     */
    static final Set<String> FILLER = Set.of("عايز", "عايزه", "عايزين", "عاوز", "عاوزه", "محتاج", "محتاجه", "محتاجين", "ممكن", "لو", "سمحت", "فضلك", "فيه", "عندكم", "عندك", "ابغي", "ابغا", "بدور", "دور", "دوري",
            "هات", "هاتلي", "وريني", "ورني", "اشوف", "شوف", "ازاي", "ايه", "ايش", "هل", "كام", "بكام", "انا", "ده", "دي", "دا", "ديه", "دول", "اللي", "يا", "فندم", "عن", "علي", "على", "لي", "ليا", "لينا", "في", "مع", "من",
            "want", "need", "looking", "for", "have", "you", "can", "get", "show", "please", "the", "and", "with", "any", "some", "do", "does", "how", "much", "is", "are", "there", "iwant", "ineed");

    /** True when a variant of the group is a request filler (so «3ayez», whose Arabic spelling is «عايز», is one too): the group names nothing. */
    static boolean isFiller(Set<String> group) { return group.stream().anyMatch(FILLER::contains) || group.stream().allMatch(v -> v.length() < 3 && !LATIN.containsKey(v)); }

    /** One group per word of the query, each group the variants worth trying (never empty). Words shorter than 2 letters are dropped. */
    static List<Set<String>> groups(String query) {
        List<Set<String>> out = new ArrayList<>();
        if (query == null) return out;
        for (String raw : FastAnswers.norm(query).split("\\s+")) {
            if (raw.length() < 2) continue;
            Set<String> g = new LinkedHashSet<>();
            String bare = raw.replaceFirst("^(?:وال|بال|ال)", "");
            if (bare.length() >= 2) g.add(bare);
            g.add(raw);
            String sq = squeeze(raw).replaceFirst("^(?:وال|بال|ال)", "");
            if (sq.length() >= 2) g.add(sq);
            for (String v : new ArrayList<>(g)) {
                List<String> ar = LATIN.get(v);
                if (ar != null) for (String a : ar) for (String piece : FastAnswers.norm(a).split("\\s+")) if (piece.length() >= 2) g.add(piece);
            }
            out.add(g);
        }
        List<Set<String>> named = new ArrayList<>(out);
        named.removeIf(QueryNormalizer::isFiller);
        return named.isEmpty() ? out : named;   // a query made only of filler words keeps them (nothing better to match)
    }

    /** True when {@code token} is {@code word} with at most one letter wrong, missing or extra (only for words long enough that this is not a different word). */
    static boolean nearlyEqual(String word, String token) {
        if (word.equals(token)) return true;
        int n = word.length(), m = token.length();
        if (Math.min(n, m) < 5 || Math.abs(n - m) > 1) return false;
        if (n == m) { int d = 0; for (int i = 0; i < n && d < 2; i++) if (word.charAt(i) != token.charAt(i)) d++; if (d <= 1) return true;   // one letter wrong
            for (int i = 0; i + 1 < n; i++) if (word.charAt(i) != token.charAt(i)) return word.charAt(i) == token.charAt(i + 1) && word.charAt(i + 1) == token.charAt(i) && word.substring(i + 2).equals(token.substring(i + 2)) && word.substring(0, i).equals(token.substring(0, i));   // two letters swapped
            return false; }
        String a = n < m ? word : token, b = n < m ? token : word;   // a is the shorter: one letter missing from it
        int i = 0; while (i < a.length() && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(i).equals(b.substring(i + 1));
    }

    /** How many of the groups the text satisfies: a group counts when the text contains any variant, or (for a word long enough) a word of the text that is one typo away from one. */
    static int matched(List<Set<String>> groups, String textNorm) {
        String[] tokens = textNorm.split("\\s+");
        int n = 0;
        for (Set<String> g : groups) {
            boolean hit = false;
            for (String v : g) { if (textNorm.contains(v)) { hit = true; break; } }
            if (!hit) outer: for (String v : g) if (v.length() >= 5) for (String t : tokens) if (t.length() >= 4 && nearlyEqual(v, t)) { hit = true; break outer; }
            if (hit) n++;
        }
        return n;
    }

    /** The significant words of a product name (without the article, three letters or more); empty when the name is too generic to identify a product by itself (a single short word). */
    static List<String> nameTokens(String title) {
        List<String> name = new ArrayList<>();
        if (title == null) return name;
        for (String t : FastAnswers.norm(title).split("\\s+")) { String bare = t.replaceFirst("^(?:وال|بال|ال)", ""); if (bare.length() >= 3) name.add(bare); }
        if (name.size() == 1 && name.get(0).length() < 6) name.clear();   // a single common word (جاكيت, شنطة, ساعة) names a category, not a product
        return name;
    }

    /** True when EVERY significant word of the product's name is in the query (one of a word's variants, or one typo away): the customer named this product. */
    static boolean namedIn(List<Set<String>> groups, List<String> nameTokens) {
        if (nameTokens.isEmpty()) return false;
        for (String t : nameTokens) {
            boolean hit = false;
            for (Set<String> g : groups) { for (String v : g) if (v.equals(t) || nearlyEqual(v, t)) { hit = true; break; } if (hit) break; }
            if (!hit) return false;
        }
        return true;
    }
}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Offline retrieval benchmark on a SYNTHETIC Egyptian catalog (30 products) with 80 labelled queries in 9 categories, written for this test. It compares the lexical matcher as it was, the
 * normalising matcher (Arabizi/English vocabulary, repeated letters, one typo), a character-trigram vector STAND-IN (what the test embedder is: not the production embedding) and the identity
 * fusion (a product whose full name is in the query comes first). It measures mechanisms on data the author wrote: it says nothing about the production embedding model, which needs a live key.
 * Run with BENCH_OVERHEAD=1 to print the tables.
 */
class RetrievalBenchmarkTest {
    record Doc(int id, String title, String body) {}
    record Q(String category, String text, Set<Integer> relevant) {}

    static final List<Doc> CATALOG = List.of(
            new Doc(1, "جاكيت بافر شتوي رجالي", "جاكيت منفوخ دافي للشتا حشو صناعي مقاوم للمطر الخفيف غسيل بالماء البارد"),
            new Doc(2, "جاكيت جلد طبيعي اسود", "جلد طبيعي 100% بطانة قطن يتنضف بقماشة مبلولة ولا يتغسل"),
            new Doc(3, "تيشيرت قطن اسود", "قطن 100% بيتغسل في الغسالة على 30 درجة وممكن ينكمش لو اتغسل ساخن"),
            new Doc(4, "تيشيرت رياضي دراي فيت", "قماش بوليستر بينشف بسرعة مناسب للجيم والجري"),
            new Doc(5, "قميص كتان صيفي", "كتان طبيعي خفيف للصيف بيتكوي وهو مبلول"),
            new Doc(6, "بنطلون جينز ازرق", "جينز استريتش مريح قصة مستقيمة"),
            new Doc(7, "بنطلون قماش كلاسيك رمادي", "قماش رسمي للشغل قصة ضيقة"),
            new Doc(8, "فستان سهرة اسود طويل", "فستان سهرة طويل قماش ساتان"),
            new Doc(9, "فستان صيفي مورد", "فستان خفيف مورد للصيف قطن"),
            new Doc(10, "شنطة ظهر لابتوب", "تشيل لابتوب 15.6 بوصة مقاومة للماء جيوب كتير"),
            new Doc(11, "شنطة يد جلد بني حريمي", "جلد صناعي بني شنطة يد انيقة"),
            new Doc(12, "محفظة جلد رجالي", "محفظة جلد طبيعي بجيوب للكروت"),
            new Doc(13, "حزام جلد بني", "حزام جلد طبيعي بمشبك معدن"),
            new Doc(14, "جزمة كلاسيك جلد اسود", "جزمة رسمية جلد طبيعي نعل مريح"),
            new Doc(15, "كوتشي رياضي ابيض", "كوتشي نعل مرن للجري والمشي"),
            new Doc(16, "ساعة يد ذكية", "بطارية 7 ايام مقاومة للماء تتابع النبض"),
            new Doc(17, "سماعة بلوتوث رياضية", "بطارية 20 ساعة مقاومة للعرق للجري"),
            new Doc(18, "شاحن موبايل سريع 30 وات", "شاحن سريع يدعم كل الموبايلات"),
            new Doc(19, "كوفية صوف شتوي", "صوف ناعم يدفي في البرد"),
            new Doc(20, "طقم ملايات قطن مصري", "قطن مصري 400 خيط ناعم"),
            new Doc(21, "بيجامة قطن اطفال", "بيجامة قطن للاطفال مريحة"),
            new Doc(22, "جاكيت جينز ازرق", "جاكيت جينز كلاسيك للربيع"),
            new Doc(23, "تيشيرت بولو ابيض", "بولو قطن بياقة"),
            new Doc(24, "شورت رياضي رجالي", "شورت خفيف للجيم"),
            new Doc(25, "كوباية سيراميك", "كوباية قهوة سيراميك"),
            new Doc(26, "مج حراري ستانلس", "بيحافظ على سخونة المشروب 8 ساعات"),
            new Doc(27, "عطر مسك رجالي", "عطر مسك ثابت"),
            new Doc(28, "نظارة شمس", "نظارة شمس حماية من الاشعة"),
            new Doc(29, "حقيبة سفر هارد", "حقيبة سفر صلبة بعجلات"),
            new Doc(30, "قبعة صوف شتوية", "قبعة صوف دافية"));

    static Set<Integer> r(int... ids) { Set<Integer> s = new LinkedHashSet<>(); for (int i : ids) s.add(i); return s; }

    static final List<Q> QUERIES = new ArrayList<>();
    static void add(String cat, String text, int... rel) { QUERIES.add(new Q(cat, text, r(rel))); }
    static {
        // exact names (Arabic)
        for (Doc d : List.of(CATALOG.get(0), CATALOG.get(2), CATALOG.get(4), CATALOG.get(9), CATALOG.get(15), CATALOG.get(18), CATALOG.get(19), CATALOG.get(24))) add("exact name", d.title(), d.id());
        // Egyptian / partial wording
        add("egyptian", "عايز جاكيت جلد", 2); add("egyptian", "فيه تيشيرت قطن؟", 3); add("egyptian", "عايزة فستان للسهرة", 8); add("egyptian", "محتاج بنطلون جينز", 6);
        add("egyptian", "ابغى شنطة للابتوب", 10); add("egyptian", "عندكم سماعة رياضية", 17); add("egyptian", "عايز كوتشي ابيض", 15); add("egyptian", "بدور على حزام جلد", 13); add("egyptian", "فين المحفظة الجلد", 12);
        // Arabizi
        add("arabizi", "3ayez jacket geld eswed", 2); add("arabizi", "tshirt cotton eswed", 3); add("arabizi", "bantalon jeans azra2", 6); add("arabizi", "shanta laptop", 10);
        add("arabizi", "sa3a smart", 16); add("arabizi", "3ayza fostan sayfy", 9); add("arabizi", "gazma classic eswed", 14); add("arabizi", "kotshi abyad", 15); add("arabizi", "jaket shitwy", 1);
        // typos and repeated letters
        add("typos", "جاكيييت بافر", 1); add("typos", "تيشرت قطن اسود", 3); add("typos", "بنطلونن جينز", 6); add("typos", "قميصص كتان", 5); add("typos", "فستانن سهرهه", 8);
        add("typos", "كوتشيي رياضي", 15); add("typos", "ساعه ذكيه", 16); add("typos", "محفضة جلد", 12); add("typos", "شاحنن موبايل", 18);
        // English / mixed
        add("english", "black cotton t-shirt", 3); add("english", "winter jacket men", 1); add("english", "leather wallet", 12); add("english", "laptop bag", 10); add("english", "smart watch waterproof", 16);
        add("english", "wool scarf winter", 19); add("english", "white sneakers", 15); add("english", "bluetooth headphones", 17);
        // attribute / care questions
        add("attribute", "التيشيرت القطن بيتغسل في الغسالة", 3); add("attribute", "هل القطن ينكمش", 3, 20, 21, 23); add("attribute", "جاكيت مقاوم للمطر", 1); add("attribute", "كتان للصيف", 5);
        add("attribute", "الجاكيت الجلد يتغسل", 2); add("attribute", "شنطة مقاومة للماء", 10); add("attribute", "ساعة بطارية 7 ايام", 16); add("attribute", "سماعة مقاومة للعرق", 17);
        // product follow-up with the name carried from the replied message (the reply-aware query prepends it)
        add("follow-up (name carried)", "تيشيرت قطن اسود هل بينكمش؟", 3); add("follow-up (name carried)", "جاكيت بافر شتوي رجالي ينفع في المطر", 1); add("follow-up (name carried)", "فستان سهرة اسود طويل مقاس كام", 8);
        add("follow-up (name carried)", "ساعة يد ذكية بتتشحن ازاي", 16); add("follow-up (name carried)", "بنطلون جينز ازرق ممكن اكبر", 6); add("follow-up (name carried)", "حزام جلد بني بكام", 13);
        // paraphrase with no shared words (needs meaning: a lexical matcher cannot do these)
        add("paraphrase (semantic)", "حاجة تدفيني في البرد", 1, 19, 30); add("paraphrase (semantic)", "عايز حاجة اجري بيها", 4, 15, 17, 24); add("paraphrase (semantic)", "هدية لراجل", 12, 27, 13);
        add("paraphrase (semantic)", "حاجة اشرب فيها قهوة", 25, 26); add("paraphrase (semantic)", "هسافر شهر", 29); add("paraphrase (semantic)", "عايز احمي عيني من الشمس", 28);
        // not sold: nothing should be returned
        add("not sold", "ايفون ١٥ برو"); add("not sold", "تلاجة سامسونج"); add("not sold", "laptop dell"); add("not sold", "غسالة اطباق"); add("not sold", "psp game console");
    }

    // ---- retrievers -----------------------------------------------------------------------------------------------------------

    static String text(Doc d) { return FastAnswers.norm(d.title() + " " + d.body()); }

    /** The matcher as it was before this change: exact words of three letters or more, the article removed, two needed when the query has two. */
    static List<Integer> legacy(String query, int k) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : FastAnswers.norm(query).split("[^\\p{L}\\p{N}]+")) { String bare = w.replaceFirst("^(?:وال|بال|ال)", ""); if (bare.length() >= 3) words.add(bare); }
        if (words.isEmpty()) return List.of();
        int need = words.size() >= 2 ? 2 : 1;
        List<Map.Entry<Doc, Integer>> scored = new ArrayList<>();
        for (Doc d : CATALOG) { int n = 0; String t = text(d); for (String w : words) if (t.contains(w)) n++; if (n >= need) scored.add(Map.entry(d, n)); }
        scored.sort(Map.Entry.<Doc, Integer>comparingByValue().reversed());
        return scored.stream().limit(k).map(e -> e.getKey().id()).toList();
    }

    static List<Integer> normalised(String query, int k) {
        List<Set<String>> groups = new ArrayList<>(QueryNormalizer.groups(query));
        groups.removeIf(g -> g.stream().noneMatch(v -> v.length() >= 3));
        if (groups.isEmpty()) return List.of();
        int need = groups.size() >= 2 ? 2 : 1;
        List<Map.Entry<Doc, Integer>> scored = new ArrayList<>();
        for (Doc d : CATALOG) { int n = QueryNormalizer.matched(groups, text(d)); if (n >= need) scored.add(Map.entry(d, n)); }
        scored.sort(Map.Entry.<Doc, Integer>comparingByValue().reversed());
        return scored.stream().limit(k).map(e -> e.getKey().id()).toList();
    }

    static Map<String, Double> trigrams(String s) {
        Map<String, Double> v = new LinkedHashMap<>();
        String t = " " + FastAnswers.norm(s) + " ";
        for (int i = 0; i + 3 <= t.length(); i++) v.merge(t.substring(i, i + 3), 1.0, Double::sum);
        return v;
    }

    static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (var e : a.entrySet()) { dot += e.getValue() * b.getOrDefault(e.getKey(), 0.0); na += e.getValue() * e.getValue(); }
        for (double x : b.values()) nb += x * x;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    static final Map<Integer, Map<String, Double>> VEC = new LinkedHashMap<>();
    static { for (Doc d : CATALOG) VEC.put(d.id(), trigrams(d.title() + " " + d.body())); }

    /** A vector stand-in: cosine of character trigrams. Always returns k results (a vector search has no «nothing»), which is why unanswerable queries are reported separately. */
    static List<Integer> vector(String query, int k) {
        Map<String, Double> q = trigrams(query);
        return VEC.entrySet().stream().sorted((a, b) -> Double.compare(cosine(q, b.getValue()), cosine(q, a.getValue()))).limit(k).map(Map.Entry::getKey).toList();
    }

    /** What the assistant now does: products whose full name is in the query first, then the vector results. */
    static List<Integer> fused(String query, int k) {
        List<Set<String>> groups = QueryNormalizer.groups(query);
        List<Integer> out = new ArrayList<>();
        for (Doc d : CATALOG) if (QueryNormalizer.namedIn(groups, QueryNormalizer.nameTokens(d.title()))) out.add(d.id());
        out = new ArrayList<>(out.subList(0, Math.min(2, out.size())));
        for (int id : vector(query, k)) if (out.size() < k && !out.contains(id)) out.add(id);
        return out;
    }

    /** Identity first, then reciprocal rank fusion of the vector stand-in and the normalising word search. */
    static List<Integer> hybrid(String query, int k) {
        List<Set<String>> groups = QueryNormalizer.groups(query);
        List<Integer> out = new ArrayList<>();
        for (Doc d : CATALOG) if (QueryNormalizer.namedIn(groups, QueryNormalizer.nameTokens(d.title()))) out.add(d.id());
        out = new ArrayList<>(out.subList(0, Math.min(2, out.size())));
        for (int id : KnowledgeIndex.rrf(List.of(vector(query, 10), normalised(query, 10)), k)) if (out.size() < k && !out.contains(id)) out.add(id);
        return out;
    }

    // ---- metrics ----------------------------------------------------------------------------------------------------------------

    record Score(double r1, double r3, double r5, double mrr, double ndcg5, int n) {}

    static Score score(List<Q> qs, java.util.function.BiFunction<String, Integer, List<Integer>> retriever) {
        double r1 = 0, r3 = 0, r5 = 0, mrr = 0, ndcg = 0; int n = 0;
        for (Q q : qs) {
            if (q.relevant().isEmpty()) continue;
            n++;
            List<Integer> got = retriever.apply(q.text(), 5);
            int rank = 0;
            for (int i = 0; i < got.size(); i++) if (q.relevant().contains(got.get(i))) { rank = i + 1; break; }
            if (rank == 1) r1++;
            if (rank >= 1 && rank <= 3) r3++;
            if (rank >= 1 && rank <= 5) r5++;
            if (rank > 0) { mrr += 1.0 / rank; ndcg += 1.0 / (Math.log(rank + 1) / Math.log(2)); }
        }
        return n == 0 ? new Score(0, 0, 0, 0, 0, 0) : new Score(r1 / n, r3 / n, r5 / n, mrr / n, ndcg / n, n);
    }

    @Test
    void theNormalisingMatcherNeverLosesAnswerableQueriesAndRecoversTheArabiziEnglishAndTypoOnes() {
        Map<String, List<Q>> byCat = new LinkedHashMap<>();
        for (Q q : QUERIES) byCat.computeIfAbsent(q.category(), c -> new ArrayList<>()).add(q);
        StringBuilder table = new StringBuilder(String.format("%n%-26s %3s | %-24s | %-24s | %-24s | %-24s%n", "category", "n", "legacy lexical (R@1/R@5/MRR)", "normalising lexical", "vector stand-in", "identity fusion"));
        table.append(String.format("(last column of each row below: hybrid = identity + rank fusion of vector stand-in and normalising lexical)%n"));
        java.util.function.Function<Score, String> f = s -> String.format("%.2f / %.2f / %.2f", s.r1(), s.r5(), s.mrr());
        for (var e : byCat.entrySet()) {
            if (e.getKey().equals("not sold")) continue;
            Score a = score(e.getValue(), RetrievalBenchmarkTest::legacy), b = score(e.getValue(), RetrievalBenchmarkTest::normalised), c = score(e.getValue(), RetrievalBenchmarkTest::vector), d = score(e.getValue(), RetrievalBenchmarkTest::fused);
            Score h = score(e.getValue(), RetrievalBenchmarkTest::hybrid);
            table.append(String.format("%-26s %3d | %-24s | %-24s | %-24s | %-24s | hybrid %s%n", e.getKey(), a.n(), f.apply(a), f.apply(b), f.apply(c), f.apply(d), f.apply(h)));
            assertThat(b.r5()).as("normalising never loses recall in " + e.getKey()).isGreaterThanOrEqualTo(a.r5());
            assertThat(b.mrr()).as("nor ranking in " + e.getKey()).isGreaterThanOrEqualTo(a.mrr() - 1e-9);
        }
        List<Q> answerable = QUERIES.stream().filter(q -> !q.relevant().isEmpty()).toList();
        Score la = score(answerable, RetrievalBenchmarkTest::legacy), lb = score(answerable, RetrievalBenchmarkTest::normalised), lc = score(answerable, RetrievalBenchmarkTest::vector), ld = score(answerable, RetrievalBenchmarkTest::fused);
        table.append(String.format("%-26s %3d | %-24s | %-24s | %-24s | %-24s%n", "ALL answerable", la.n(), f.apply(la), f.apply(lb), f.apply(lc), f.apply(ld)));
        Score lh = score(answerable, RetrievalBenchmarkTest::hybrid);
        table.append(String.format("ALL hybrid: R@1 %.2f R@5 %.2f MRR %.2f%n", lh.r1(), lh.r5(), lh.mrr()));
        table.append(String.format("NDCG@5: legacy %.3f, normalising %.3f, vector stand-in %.3f, identity fusion %.3f, hybrid %.3f%n", la.ndcg5(), lb.ndcg5(), lc.ndcg5(), ld.ndcg5(), lh.ndcg5()));
        // not sold: how often each retriever invents a result
        List<Q> notSold = QUERIES.stream().filter(q -> q.category().equals("not sold")).toList();
        long fa = notSold.stream().filter(q -> !legacy(q.text(), 5).isEmpty()).count(), fb = notSold.stream().filter(q -> !normalised(q.text(), 5).isEmpty()).count();
        table.append(String.format("Unanswerable queries (%d): legacy lexical returned something for %d, normalising lexical for %d, a vector search always returns results (the assistant's rules must say «we do not have it»)%n", notSold.size(), fa, fb));
        // latency of one query over the catalog
        long t0 = System.nanoTime(); for (int i = 0; i < 200; i++) for (Q q : answerable) normalised(q.text(), 5); double msN = (System.nanoTime() - t0) / 1e6 / 200 / answerable.size();
        t0 = System.nanoTime(); for (int i = 0; i < 200; i++) for (Q q : answerable) legacy(q.text(), 5); double msL = (System.nanoTime() - t0) / 1e6 / 200 / answerable.size();
        table.append(String.format("Lexical match per query over 30 products: legacy %.3f ms, normalising %.3f ms%n", msL, msN));
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.println("RETRIEVAL_BENCHMARK" + table);
        assertThat(lb.r5()).as("overall").isGreaterThan(la.r5());
        assertThat(lb.mrr()).isGreaterThan(la.mrr());
        assertThat(ld.r1()).as("naming a product puts it first").isGreaterThanOrEqualTo(lc.r1());
        assertThat(fb).as("normalising does not start inventing matches for things the shop does not sell").isLessThanOrEqualTo(fa + 1);
    }

    @Test
    void theNormaliserHandlesTheWordsItIsBuiltFor() {
        assertThat(QueryNormalizer.groups("3ayez jacket eswed")).flatExtracting(g -> g).contains("جاكيت", "اسود", "عايز");
        assertThat(QueryNormalizer.squeeze("جاكيييييت")).isEqualTo("جاكيت");
        assertThat(QueryNormalizer.nearlyEqual("تيشيرت", "تيشرت")).isTrue();
        assertThat(QueryNormalizer.nearlyEqual("جاكيت", "جاكيتت")).isTrue();
        assertThat(QueryNormalizer.nearlyEqual("جاكيت", "بنطلون")).isFalse();
        assertThat(QueryNormalizer.nearlyEqual("قطن", "قطط")).as("short words are never fuzzy").isFalse();
        assertThat(QueryNormalizer.nameTokens("شنطة")).as("a generic one-word name identifies nothing").isEmpty();
        assertThat(QueryNormalizer.namedIn(QueryNormalizer.groups("عايز جاكيت بافر شتوي رجالي بكام"), QueryNormalizer.nameTokens("جاكيت بافر شتوي رجالي"))).isTrue();
        assertThat(QueryNormalizer.namedIn(QueryNormalizer.groups("عايز جاكيت جلد"), QueryNormalizer.nameTokens("جاكيت بافر شتوي رجالي"))).isFalse();
    }
}

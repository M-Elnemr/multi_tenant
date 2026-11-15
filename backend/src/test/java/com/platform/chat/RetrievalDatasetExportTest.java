package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Runs the lexical strategies of the code (the matcher as it was, the normalising matcher, the named-product step) plus a BM25 word search and a character-trigram stand-in over the FIXED
 * dataset (src/test/resources/retrieval/dataset.json: 3 synthetic shops, 90 products, 201 queries) and exports each strategy's top-10 per query to build/retrieval/rankings.json. The metrics
 * are computed once, for every strategy and every embedding model alike, by backend/evals/retrieval/evaluate.py, so no strategy is scored by a different implementation.
 */
class RetrievalDatasetExportTest {
    static final ObjectMapper JSON = new ObjectMapper();

    record Doc(int id, String title, String body) {}

    static String text(Doc d) { return FastAnswers.norm(d.title() + " " + d.body()); }

    static List<Integer> legacy(List<Doc> docs, String query, int k) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : FastAnswers.norm(query).split("[^\\p{L}\\p{N}]+")) { String bare = w.replaceFirst("^(?:وال|بال|ال)", ""); if (bare.length() >= 3) words.add(bare); }
        if (words.isEmpty()) return List.of();
        int need = words.size() >= 2 ? 2 : 1;
        List<Map.Entry<Doc, Integer>> scored = new ArrayList<>();
        for (Doc d : docs) { int n = 0; String t = text(d); for (String w : words) if (t.contains(w)) n++; if (n >= need) scored.add(Map.entry(d, n)); }
        scored.sort(Map.Entry.<Doc, Integer>comparingByValue().reversed());
        return scored.stream().limit(k).map(e -> e.getKey().id()).toList();
    }

    static List<Integer> normalising(List<Doc> docs, String query, int k) {
        List<Set<String>> groups = new ArrayList<>(QueryNormalizer.groups(query));
        groups.removeIf(g -> g.stream().noneMatch(v -> v.length() >= 3));
        if (groups.isEmpty()) return List.of();
        int need = groups.size() >= 2 ? 2 : 1;
        List<Map.Entry<Doc, Integer>> scored = new ArrayList<>();
        for (Doc d : docs) { int n = QueryNormalizer.matched(groups, text(d)); if (n >= need) scored.add(Map.entry(d, n)); }
        scored.sort(Map.Entry.<Doc, Integer>comparingByValue().reversed());
        return scored.stream().limit(k).map(e -> e.getKey().id()).toList();
    }

    static List<Integer> identity(List<Doc> docs, String query, int k) {
        List<Set<String>> groups = QueryNormalizer.groups(query);
        List<Integer> out = new ArrayList<>();
        for (Doc d : docs) if (QueryNormalizer.namedIn(groups, QueryNormalizer.nameTokens(d.title()))) out.add(d.id());
        return out.subList(0, Math.min(k, out.size()));
    }

    /** Okapi BM25 (k1 1.2, b 0.75) over the normalised words of title and body: a standard full-text ranking, as a lexical baseline that does not need the normaliser. */
    static List<Integer> bm25(List<Doc> docs, String query, int k) {
        List<String[]> toks = new ArrayList<>();
        Map<String, Integer> df = new LinkedHashMap<>();
        double avg = 0;
        for (Doc d : docs) { String[] t = text(d).split("\\s+"); toks.add(t); avg += t.length; for (String w : new LinkedHashSet<>(List.of(t))) df.merge(w, 1, Integer::sum); }
        avg /= docs.size();
        List<String> q = new ArrayList<>();
        for (String w : FastAnswers.norm(query).split("\\s+")) { String bare = w.replaceFirst("^(?:وال|بال|ال)", ""); if (bare.length() >= 2) q.add(bare); }
        List<Map.Entry<Integer, Double>> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            double s = 0; String[] t = toks.get(i);
            for (String w : q) {
                int tf = 0; for (String x : t) if (x.equals(w)) tf++;
                if (tf == 0) continue;
                double idf = Math.log(1 + (docs.size() - df.getOrDefault(w, 0) + 0.5) / (df.getOrDefault(w, 0) + 0.5));
                s += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * t.length / avg));
            }
            if (s > 0) scored.add(Map.entry(docs.get(i).id(), s));
        }
        scored.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
        return scored.stream().limit(k).map(Map.Entry::getKey).toList();
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

    static List<Integer> trigram(List<Doc> docs, String query, int k) {
        Map<String, Double> q = trigrams(query);
        return docs.stream().sorted((a, b) -> Double.compare(cosine(q, trigrams(b.title() + " " + b.body())), cosine(q, trigrams(a.title() + " " + a.body())))).limit(k).map(Doc::id).toList();
    }

    @Test
    void exportsTheRankingsOfEveryLexicalStrategyOverTheFixedDataset() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/retrieval/dataset.json")) { root = JSON.readTree(in); }
        ObjectNode out = JSON.createObjectNode();
        int queries = 0;
        for (JsonNode shop : root.path("shops")) {
            List<Doc> docs = new ArrayList<>();
            for (JsonNode p : shop.path("products")) docs.add(new Doc(p.path("id").asInt(), p.path("title").asText(), p.path("body").asText()));
            ArrayNode rows = out.putObject(shop.path("name").asText()).putArray("rankings");
            for (JsonNode q : shop.path("queries")) {
                String text = q.path("text").asText();
                ObjectNode r = rows.addObject();
                r.put("text", text);
                r.set("legacy", JSON.valueToTree(legacy(docs, text, 10)));
                r.set("normalising", JSON.valueToTree(normalising(docs, text, 10)));
                r.set("bm25", JSON.valueToTree(bm25(docs, text, 10)));
                r.set("trigram", JSON.valueToTree(trigram(docs, text, 10)));
                r.set("identity", JSON.valueToTree(identity(docs, text, 3)));
                queries++;
            }
        }
        File dest = new File("build/retrieval/rankings.json");
        dest.getParentFile().mkdirs();
        JSON.writerWithDefaultPrettyPrinter().writeValue(dest, out);
        assertThat(queries).as("the fixed dataset").isEqualTo(201);
    }
}

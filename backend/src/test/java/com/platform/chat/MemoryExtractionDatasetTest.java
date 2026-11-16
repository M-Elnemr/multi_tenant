package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Precision and recall of the memory's fact extraction over a FIXED labelled set (src/test/resources/memory/extraction.json: messages in Egyptian Arabic, English and Arabizi with their
 * exact expected facts, including messages that must produce NO fact: prices, phone numbers, negations, product-scoped dislikes, recall questions). A fact counts as correct only when
 * type, key and value all match. Run with BENCH_OVERHEAD=1 to print the table; the thresholds below were set AFTER the labels were written and are the quality bar, not a fit to the output.
 */
class MemoryExtractionDatasetTest {
    static String sig(String type, String key, String value) { return type + "|" + key + "|" + value; }

    @Test
    void factExtractionPrecisionAndRecallOnTheFixedLabelledMessages() throws Exception { check("/memory/extraction.json", "MEMORY_EXTRACTION"); }

    /** Messages written AFTER the extractor was tuned and run once before any fix for them: the honest number. Once a miss here is fixed it moves to extraction.json and stops being held out. */
    @Test
    void factExtractionOnTheHeldOutMessages() throws Exception { check("/memory/extraction-holdout.json", "MEMORY_HOLDOUT"); }

    private void check(String resource, String label) throws Exception {
        JsonNode rows;
        try (InputStream in = getClass().getResourceAsStream(resource)) { rows = new ObjectMapper().readTree(in); }
        Map<String, int[]> byCat = new LinkedHashMap<>();   // category -> {expected, extracted, correct}
        int[] all = new int[3];
        StringBuilder wrong = new StringBuilder();
        for (JsonNode r : rows) {
            Set<String> expected = new LinkedHashSet<>(), got = new LinkedHashSet<>();
            for (JsonNode e : r.path("expect")) expected.add(sig(e.path("type").asText(), e.path("key").asText(), e.path("value").asText()));
            for (var c : MemoryFacts.extract(r.path("text").asText())) got.add(sig(c.type(), c.key(), c.value()));
            // a temporary preference must live one day, not ninety
            for (JsonNode e : r.path("expect")) if (e.has("ttl")) {
                long ttl = MemoryFacts.extract(r.path("text").asText()).stream().filter(c -> c.key().equals(e.path("key").asText())).findFirst().map(MemoryFacts.Candidate::ttlDays).orElse(-1L);
                if (ttl != e.path("ttl").asLong()) wrong.append(String.format("  «%s»%n     ttl of %s expected %d got %d%n", r.path("text").asText(), e.path("key").asText(), e.path("ttl").asLong(), ttl));
            }
            int correct = 0; for (String g : got) if (expected.contains(g)) correct++;
            int[] c = byCat.computeIfAbsent(r.path("category").asText(), k -> new int[3]);
            c[0] += expected.size(); c[1] += got.size(); c[2] += correct; all[0] += expected.size(); all[1] += got.size(); all[2] += correct;
            if (!got.equals(expected)) wrong.append(String.format("  «%s»%n     expected %s%n     got      %s%n", r.path("text").asText(), expected, got));
        }
        double p = all[1] == 0 ? 1 : all[2] / (double) all[1], rec = all[0] == 0 ? 1 : all[2] / (double) all[0];
        StringBuilder t = new StringBuilder(String.format("%n" + label + " %d messages, %d expected facts, %d extracted, %d correct: precision %.3f recall %.3f F1 %.3f%n", rows.size(), all[0], all[1], all[2], p, rec, 2 * p * rec / Math.max(1e-9, p + rec)));
        for (var e : byCat.entrySet()) { int[] c = e.getValue(); t.append(String.format("  %-42s expected %2d extracted %2d correct %2d%n", e.getKey(), c[0], c[1], c[2])); }
        if (wrong.length() > 0) t.append("Mismatches:\n").append(wrong);
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.println(t);
        assertThat(wrong.toString()).as("every labelled message must extract exactly its expected facts\n" + t).isEmpty();
    }
}

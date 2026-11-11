package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An ungraded conversation (the judge gave no usable answer) is not a score of 0: it is left out of every percentage and counted on its own. */
class AiEvalSummaryTest {
    static Map<String, Object> r(String category, int passed, int of, boolean failed) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("category", category, "passed", passed, "of", of));
        if (failed) m.put("judgeFailed", true);
        return m;
    }

    @Test
    void judgeFailuresAreCountedApartAndDoNotLowerThePercentage() {
        var s = AiEvalService.summary(List.of(r("a", 3, 4, false), r("a", 4, 4, false), r("b", 0, 4, true)));
        assertThat(s).containsEntry("overallPct", 88L).containsEntry("checks", 8).containsEntry("ungraded", 1);
        @SuppressWarnings("unchecked") Map<String, Object> cats = (Map<String, Object>) s.get("byCategory");
        assertThat(cats).containsEntry("a", 88L).doesNotContainKey("b");
    }
}

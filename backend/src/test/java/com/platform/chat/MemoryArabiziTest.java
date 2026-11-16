package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Arabizi (Arabic in Latin letters) and mixed Arabic/English retail phrases. These were found by probing after the main datasets were tuned, so they are an in-sample check of the Arabizi
 * additions, not an independent benchmark. The most important rows are the negations: «mesh 3ayez abyad» must never be stored as «likes white».
 */
class MemoryArabiziTest {
    static List<String> facts(String text) { return MemoryFacts.extract(text).stream().map(c -> c.type() + ":" + c.key() + "=" + c.value()).toList(); }

    @Test
    void anArabiziNegationIsNeverStoredAsAPositivePreference() {
        assertThat(facts("mesh 3ayez abyad")).containsExactly("COLOR:color:white=avoid");
        assertThat(facts("mesh 3ayza el ahmar")).containsExactly("COLOR:color:red=avoid");
        assertThat(facts("msh 3ayez eswed")).containsExactly("COLOR:color:black=avoid");
        assertThat(facts("mesh bahib el aswad")).containsExactly("COLOR:color:black=avoid");
        assertThat(facts("I don't want red")).containsExactly("COLOR:color:red=avoid");
    }

    @Test
    void anArabiziNegatedSizeIsNotStoredAsTheirSize() {
        assertThat(facts("msh 3ayez size M")).noneMatch(f -> f.startsWith("SIZE"));
        assertThat(facts("mesh size L")).noneMatch(f -> f.startsWith("SIZE"));
        assertThat(facts("my size is not L")).noneMatch(f -> f.startsWith("SIZE"));
    }

    @Test
    void positiveArabiziColoursAndSizesStillWork() {
        assertThat(facts("3ayez jacket aswad size L")).contains("COLOR:color:black=like", "SIZE:size=L");
        assertThat(facts("3ayza fostan ahmar size M")).contains("COLOR:color:red=like", "SIZE:size=M");
        assertThat(facts("bahib el aswad wel kohly")).contains("COLOR:color:black=like", "COLOR:color:navy=like");
    }

    @Test
    void arabiziItemsGetTheirOwnSizeAndTheArabiziSizeWordsAreRead() {
        assertThat(facts("size XL fel jacket w 42 fel pantalon")).contains("SIZE:size:jacket=XL", "SIZE:size:pants=42");
        assertThat(facts("ma2as el jacket XL")).contains("SIZE:size:jacket=XL");
        assertThat(facts("ana el ma2as bta3y XL")).contains("SIZE:size=XL");
        assertThat(facts("jacket ma2aso L")).contains("SIZE:size:jacket=L");
    }

    @Test
    void arabiziBudgetsAreReadWholeAndACapIsABudgetNotAPrice() {
        assertThat(facts("el budget bta3y 2000 geneh")).contains("BUDGET:budget=2000");
        assertThat(facts("meezaneyety 3 alaf")).contains("BUDGET:budget=3000");
        assertThat(facts("meezaneyety 2 alaf")).contains("BUDGET:budget=2000");
        assertThat(facts("mesh hasraf aktar men 1500")).contains("BUDGET:budget=1500");
        assertThat(facts("budget around 2k")).contains("BUDGET:budget=2000");
    }

    @Test
    void whatIsNotReadStaysUnread() {
        assertThat(facts("ana Ahmed")).isEmpty();   // a name in Latin letters is not inferred (a stoplist cannot cover «ana tired», «ana here»): the assistant asks instead
        assertThat(facts("3ayez ash2a")).noneMatch(f -> f.startsWith("BUDGET") || f.startsWith("SIZE"));
        assertThat(facts("el pantalon 42 kam?")).noneMatch(f -> f.startsWith("SIZE"));   // a question about a product, not their size
    }
}

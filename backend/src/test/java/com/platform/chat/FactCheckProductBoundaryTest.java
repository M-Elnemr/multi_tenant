package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A paraphrased name of one product must never be read as another product: its colours, prices or stock would be written into the answer (seen live: a bags list got the school shoe's colours). */
class FactCheckProductBoundaryTest {
    private static List<String> slugs(String text) {
        return FactCheck.mentions(text, FactCheckTest.FASH.names()).stream().map(FactCheck.Mention::slug).toList();
    }

    private static List<String> kinds(String customer, String reply) {
        return FactCheck.check(customer, reply, FactCheckTest.FASH, List.of()).stream().map(FactCheck.Problem::kind).toList();
    }

    @Test
    void paraphrasedBagIsNotTheSchoolShoe() {
        for (String t : List.of("شنطة مدرسة أطفال متاحة باللون الأحمر", "عندنا شنطة مدرسة أطفال لونها أسود", "- شنطة مدرسة أطفال أسود", "الشنطة مدرسة أطفال بالأحمر"))
            assertThat(slugs(t)).as(t).doesNotContain("حذاء-مدرسة-أطفال");
    }

    @Test
    void noColourFromAnUnrelatedProductIsWrittenIntoABagAnswer() {
        var problems = FactCheck.check("عندك شنط ايه", "شنطة مدرسة أطفال متاحة باللون الأحمر", FactCheckTest.FASH, List.of());
        assertThat(problems.stream().map(FactCheck.Problem::fixed)).noneMatch(f -> f != null && f.contains("حذاء مدرسة أطفال"));
    }

    @Test
    void otherKindWordsBeforeASharedTailDoNotBorrowTheProduct() {
        assertThat(slugs("كوتشي رياضي نسائي")).doesNotContain("شنطة-رياضة");
        assertThat(slugs("شنطة أطفال بالأضواء")).doesNotContain("كوتشي-أطفال-بالأضواء");
        assertThat(slugs("سويت أطفال قطن")).doesNotContain("شورت-أطفال-قطن");
    }

    @Test
    void theRealNamesAndTheShortenedFormStillMatch() {
        assertThat(slugs("حذاء مدرسة أطفال بكام؟")).contains("حذاء-مدرسة-أطفال");
        assertThat(slugs("شنطة مدرسة ولادي بكام؟")).contains("شنطة-مدرسة-ولادي");
        assertThat(slugs("عايز مدرسة أطفال")).as("a window with nothing before it keeps working").isNotNull();
    }

    @Test
    void aRealWrongColourOfTheRealProductIsStillCaught() {
        assertThat(kinds("حذاء مدرسة أطفال", "حذاء مدرسة أطفال متاح باللون الأحمر")).contains("INVENTED_COLOR");
    }

    /**
     * Property over every product of every fixture shop: say its name with the kind word (the first word) swapped for another product's kind word, which is a different thing
     * («شنطة» for «حذاء»). That phrase must never come back as the original product, whose colours, prices and stock would be written into the answer.
     */
    @Test
    void aProductNameWithAnotherKindWordIsNeverTheOriginalProductInAnyShop() {
        int probes = 0;
        List<String> leaks = new java.util.ArrayList<>();
        for (var shop : List.of(FactCheckTest.FASH, FactCheckTest.REST, FactCheckTest.TECH)) {
            var names = shop.names();
            var heads = names.stream().filter(n -> !n.words().isEmpty()).map(n -> n.words().get(0)).distinct().toList();
            for (var p : names) {
                var w = p.words();
                if (w.size() < 3) continue;
                String rest = String.join(" ", w.subList(1, w.size()));
                for (String h : heads) {
                    if (h.equals(w.get(0)) || w.contains(h)) continue;
                    String phrase = h + " " + rest;
                    if (names.stream().anyMatch(n -> String.join(" ", n.words()).equals(phrase))) continue;   // that really is another product
                    probes++;
                    String text = "عندنا " + phrase + " بلون الاسود والاحمر";
                    if (FactCheck.mentions(text, names).stream().anyMatch(m -> m.slug().equals(p.slug()))) leaks.add(phrase + "  ->  " + p.slug());
                }
            }
        }
        assertThat(probes).as("the property was exercised").isGreaterThan(50);
        assertThat(leaks).as("a swapped kind word must not resolve to the original product (%d probes)", probes).isEmpty();
    }

    @Test
    void aValueOfTheProductsOtherOptionIsNotAnInventedColour() {
        // round 4 of the 2026-11-25 test: «أسود + سويتش بني» — «بني» is the keyboard's switch, not a colour it lacks; the answer must not be replaced by its colours line
        var problems = FactCheck.check("عايز كيبورد ميكانيكال زينترا أسود بني", "تمام، كيبورد ميكانيكال زينترا أسود بالسويتش البني متوفر بـ 1499 جنيه.", FactCheckTest.TECH, List.of());
        assertThat(problems.stream().map(FactCheck.Problem::kind)).doesNotContain("INVENTED_COLOR");
    }

}

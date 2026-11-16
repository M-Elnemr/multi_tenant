package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.MemoryFacts.Candidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the memory reads out of a customer's words, and what it must NOT (pure extraction, no database). */
class MemoryFactsExtractionTest {
    static Candidate one(String text, String type) {
        var found = MemoryFacts.extract(text).stream().filter(c -> c.type().equals(type)).toList();
        assertThat(found).as(type + " in: " + text).hasSize(1);
        return found.get(0);
    }

    static boolean none(String text, String type) { return MemoryFacts.extract(text).stream().noneMatch(c -> c.type().equals(type)); }

    @Test
    void budgetInEgyptianArabicEnglishAndArabiziWithArabicIndicDigits() {
        assertThat(one("ميزانيتي ٦٠٠ جنيه", "BUDGET").value()).isEqualTo("600");
        assertThat(one("ميزانيتي ٦٠٠ جنيه", "BUDGET").confidence()).isEqualTo(1.0);
        assertThat(one("عايز حاجة في حدود 500", "BUDGET").value()).isEqualTo("500");
        assertThat(one("عايز حاجة في حدود 500", "BUDGET").confidence()).as("not outright: a reading").isLessThan(0.8);
        assertThat(one("مش اكتر من ٤٠٠ جنيه لو سمحت", "BUDGET").value()).isEqualTo("400");
        assertThat(one("my budget is 750", "BUDGET").value()).isEqualTo("750");
        assertThat(one("budget 1500 max", "BUDGET").value()).isEqualTo("1500");
    }

    @Test
    void aProductPriceOfferAPhoneNumberOrAShortNumberIsNotABudget() {
        assertThat(none("ينفع تخليه ٨٠٠؟", "BUDGET")).as("a bargaining offer is not a budget statement").isTrue();
        assertThat(none("رقمي 01012345678", "BUDGET")).isTrue();
        assertThat(none("ميزانيتي 5", "BUDGET")).isTrue();
        assertThat(none("الجاكيت بـ 1200 جنيه", "BUDGET")).isTrue();
    }

    @Test
    void sizeInLettersArabicWordsAndNumbers() {
        assertThat(one("مقاسي L", "SIZE").value()).isEqualTo("L");
        assertThat(one("مقاسي xl", "SIZE").value()).isEqualTo("XL");
        assertThat(one("انا بلبس ميديم", "SIZE").value()).isEqualTo("M");
        assertThat(one("مقاسي لارج", "SIZE").value()).isEqualTo("L");
        assertThat(one("i wear size M", "SIZE").value()).isEqualTo("M");
        assertThat(one("مقاس 42", "SIZE").value()).isEqualTo("42");
        assertThat(none("مقاس 7", "SIZE")).as("not a clothing or shoe size").isTrue();
        assertThat(none("عايز size guide", "SIZE")).isTrue();
    }

    @Test
    void colourLikesDislikesAndArabiziButNeverAMereMention() {
        assertThat(one("بحب اللون الاسود", "COLOR").key()).isEqualTo("color:black");
        assertThat(one("بحب اللون الاسود", "COLOR").value()).isEqualTo("like");
        var avoid = one("مش بحب الاحمر خالص", "COLOR");
        assertThat(avoid.key()).isEqualTo("color:red");
        assertThat(avoid.value()).isEqualTo("avoid");
        assertThat(one("3ayez eswed", "COLOR").key()).isEqualTo("color:black");
        assertThat(one("i don't like blue", "COLOR").value()).isEqualTo("avoid");
        assertThat(one("عايز بيج", "COLOR").key()).isEqualTo("color:beige");
        assertThat(none("الجاكيت الاسود بكام؟", "COLOR")).as("a colour merely mentioned says nothing about what they want").isTrue();
    }

    @Test
    void conditionsNameAndIntentWithHonestConfidence() {
        assertThat(one("عايز بدون جلد", "CONSTRAINT").value()).isEqualTo("without جلد");
        assertThat(one("مش عايز حاجة ضيقة", "CONSTRAINT").value()).startsWith("not ");
        assertThat(none("مش عايز الاحمر", "CONSTRAINT")).as("colours are colour facts, not conditions").isTrue();
        assertThat(one("اسمي منى", "NAME").value()).isEqualTo("منى");
        var intent = one("خلاص هاخده", "INTENT");
        assertThat(intent.confidence()).as("a reading of what they said, never a confirmation").isLessThan(0.8);
        assertThat(intent.ttlDays()).isLessThanOrEqualTo(3);
    }

    @Test
    void identifiersAndSensitiveDataNeverBecomeFacts() {
        for (String text : List.of("رقمي 01012345678 ومقاسي L", "ايميلي a.b@x.com", "عنواني 5 شارع التحرير الدور 3", "مش عايز اتصل على 01012345678"))
            for (var c : MemoryFacts.extract(text)) assertThat(c.value() + c.key()).doesNotContain("01012345678").doesNotContain("@").doesNotContain("التحرير");
        assertThat(MemoryFacts.extract("رقمي 01012345678 ومقاسي L")).extracting(Candidate::type).containsExactly("SIZE");
    }

    @Test
    void theBlockLabelsBudgetAsNotAPriceMarksInferenceShowsProvenanceAndNeverClaimsToBeShopData() {
        var facts = List.of(new MemoryFacts.Fact(1, "BUDGET", "budget", "600", 1.0, "ميزانيتي ٦٠٠ جنيه", Instant.parse("2026-11-02T10:00:00Z"), 5),
                new MemoryFacts.Fact(2, "INTENT", "intent:purchase", "wants to order", 0.6, "خلاص هاخده", Instant.parse("2026-11-02T10:05:00Z"), 9),
                new MemoryFacts.Fact(3, "COLOR", "color:red", "avoid", 0.9, "مش بحب الاحمر", Instant.parse("2026-11-02T10:06:00Z"), 11));
        String b = MemoryFacts.block(facts);
        assertThat(b).contains("NOT shop data").contains("never a price, stock, variant, order or payment status").contains("A newer statement of theirs in the conversation above beats anything here")
                .contains("Their budget: 600 (what they want to spend, NOT a product's price)").contains("said on 2026-11-02").contains("«ميزانيتي ٦٠٠ جنيه»")
                .contains("Their purchase intent: wants to order [inferred, not confirmed]").contains("Colour they do NOT want: red");
        assertThat(b).doesNotContain("[inferred, not confirmed]  — said on 2026-11-02: «ميزانيتي");   // the firm fact carries no inference mark
        assertThat(MemoryFacts.block(List.of())).isEmpty();
    }

    static List<String> sig(String text) { return MemoryFacts.extract(text).stream().map(c -> c.type() + "|" + c.key() + "|" + c.value()).toList(); }

    @Test
    void oneCustomerKeepsADifferentSizeForEachKindOfThing() {
        assertThat(sig("مقاسي في الجاكيت L وفي البنطلون 32")).containsExactlyInAnyOrder("SIZE|size:jacket|L", "SIZE|size:pants|32");
        assertThat(sig("top size L and blouse size XL")).containsExactlyInAnyOrder("SIZE|size:top|L", "SIZE|size:blouse|XL");
        assertThat(sig("مقاسي L ومقاس الجزمة 41")).containsExactlyInAnyOrder("SIZE|size|L", "SIZE|size:shoes|41");
        assertThat(sig("عايزة فستان مقاسي M")).as("their own size, said after the thing they want: general, not the dress's").contains("SIZE|size|M");
        assertThat(sig("i wear M in shirts and 32 in jeans")).as("«shirts» is not «shirt» plus size S").containsExactlyInAnyOrder("SIZE|size:shirt|M", "SIZE|size:jeans|32");
    }

    @Test
    void aSizeThatIsNegatedScopedToAProductSomebodyElsesOrAskedAboutIsNotTheCustomers() {
        assertThat(sig("i don't wear size L")).isEmpty();
        assertThat(sig("ده مش مقاسي L")).isEmpty();
        assertThat(sig("مقاسي XL مش L")).containsExactly("SIZE|size|XL");
        assertThat(sig("لا مقاسي M مش L")).as("a bare «لا» opens a correction, it does not negate").containsExactly("SIZE|size|M");
        assertThat(sig("مقاس L ده ضيق عليا")).as("the size of that product").isEmpty();
        assertThat(sig("مقاس ابني L")).as("somebody else's").isEmpty();
        assertThat(sig("عندكم بنطلون جينز مقاس 32؟")).as("a stock question is not a statement of their size").isEmpty();
        assertThat(MemoryFacts.negatedSizes("i don't wear size L")).extracting(c -> c.key() + "|" + c.value()).containsExactly("size|L");
        assertThat(MemoryFacts.negatedSizes("مقاسي L")).isEmpty();
    }

    @Test
    void aSizeSaidAgainAsACorrectionReplacesTheOneBeforeIt() {
        assertThat(sig("مقاسي L لا قصدي XL")).containsExactly("SIZE|size|XL");
        assertThat(sig("size M sorry i mean L")).containsExactly("SIZE|size|L");
    }

    @Test
    void thousandsInEveryWrittenFormAreTheWholeAmountAndOnlyABudgetWordMakesThemOne() {
        for (String[] c : new String[][] {{"ميزانيتي 3 الاف جنيه", "3000"}, {"ميزانيتي ٢ الاف", "2000"}, {"budget 2k", "2000"}, {"ميزانيتي 1.5 الف", "1500"}, {"ميزانيتي 2.5k", "2500"}, {"ميزانيتي الفين", "2000"},
                {"ميزانيتي تلات الاف", "3000"}, {"ميزانيتي ٤ آلاف", "4000"}, {"ميزانيتي ١٠٠ الف جنيه", "100000"}, {"ميزانيتي 5,000", "5000"}, {"ميزانيتي 12,500", "12500"}, {"مش هصرف اكتر من ١٠٠٠", "1000"}, {"معييش اكتر من ٨٠٠", "800"}})
            assertThat(sig(c[0])).as(c[0]).contains("BUDGET|budget|" + c[1]);
        assertThat(sig("الجاكيت بـ 3 الاف جنيه")).as("a product's price is not a budget").isEmpty();
        assertThat(sig("عايز اشتري 3 قطع")).isEmpty();
    }

    @Test
    void aTemporaryWantLivesADayAndAScopedColourIsNotTheirTaste() {
        assertThat(MemoryFacts.extract("النهارده بس عايز احمر")).extracting(MemoryFacts.Candidate::ttlDays).containsExactly(1L);
        assertThat(MemoryFacts.extract("لهدية صاحبتي عايز وردي")).extracting(MemoryFacts.Candidate::ttlDays).containsExactly(1L);
        assertThat(sig("مش بحب الاسود في الجاكيت ده")).isEmpty();
        assertThat(sig("i don't like blue in shirts")).isEmpty();
        assertThat(sig("بحب الاسود والكحلي")).containsExactlyInAnyOrder("COLOR|color:black|like", "COLOR|color:navy|like");
        assertThat(sig("عايز جاكيت اسود مش ابيض")).containsExactlyInAnyOrder("COLOR|color:black|like", "COLOR|color:white|avoid");
    }

    @Test
    void negatedOrDoubtedIntentIsNotAnIntent() {
        for (String t : List.of("مش هاخده", "هاخده؟ لا مش متاكد", "مش هاخده غير لو في خصم", "هاخدها ولا لا؟")) assertThat(sig(t)).as(t).isEmpty();
        assertThat(sig("خلاص هاخده")).containsExactly("INTENT|intent:purchase|wants to order");
    }

    @Test
    void aVeryLongMessageIsReadInFullQuicklyAndWhatItStatesAtTheEndIsNotLost() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6000; i++) b.append(i % 7 == 0 ? "عايز اعرف اكتر عن المنتج. " : "كلام كتير بيتقال هنا والموضوع طويل جدا ");
        String filler = b.toString();
        long t = System.nanoTime();
        var found = sig(filler + " ميزانيتي ٩٠٠ ومقاسي في الجزمة 42 ومش بحب الاحمر");
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertThat(found).as("a message of %d characters", filler.length()).contains("BUDGET|budget|900", "SIZE|size:shoes|42", "COLOR|color:red|avoid");
        assertThat(ms).as("extraction time for a very long message (ms)").isLessThan(3000);
        StringBuilder oneLine = new StringBuilder("ميزانيتي ٥٠٠ ");
        for (int i = 0; i < 40_000; i++) oneLine.append("كلمة ");
        assertThat(sig(oneLine.toString())).as("one huge sentence is clipped, not hung on").contains("BUDGET|budget|500");
        assertThat(MemoryFacts.chunks("a. ".repeat(5000))).hasSizeLessThanOrEqualTo(MemoryFacts.MAX_CHUNKS);
    }

    @Test
    void aBareNameStatementIsAnInferredNameButAStateOfBeingIsNot() {
        assertThat(MemoryFacts.extract("انا احمد")).extracting(c -> c.key() + "|" + c.value() + "|" + (c.confidence() < 0.8)).containsExactly("name|احمد|true");
        for (String t : List.of("انا تعبان اوي", "انا تمام", "انا مستعجل", "انا عايز جاكيت")) assertThat(sig(t)).as(t).noneMatch(x -> x.startsWith("NAME"));
    }
}

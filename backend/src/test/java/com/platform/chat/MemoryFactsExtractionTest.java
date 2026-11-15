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
}

package com.platform.chat.unofficial;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The human-like rules of both profiles (Messenger / Instagram manual style, WhatsApp own number). Pure logic, no network, no database. */
class HumanPolicyTest {
    final HumanPolicy manual = new HumanPolicy(HumanPolicy.manualDefaults());
    final HumanPolicy wa = new HumanPolicy(HumanPolicy.whatsappDefaults());
    final Random r = new Random(42);

    // ---- manual style ---------------------------------------------------------------------------------------------------------

    @Test
    void manualGapIsFortyToOneTwentyPlusJitterAndNotUniform() {
        double sum = 0, min = 1e9, max = 0;
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            long ms = manual.gapMillis(r);
            double s = ms / 1000.0;
            assertThat(s).isBetween(40.0, 135.0);   // 40-120 plus up to 15 s of jitter
            sum += s; min = Math.min(min, s); max = Math.max(max, s);
            distinct.add(ms);
        }
        assertThat(distinct.size()).isGreaterThan(2000);   // never a fixed rhythm
        assertThat(sum / 3000).isBetween(60.0, 90.0);       // clustered around the middle (log-normal), not the uniform spread
        assertThat(min).isLessThan(55.0);
        assertThat(max).isGreaterThan(100.0);
    }

    @Test
    void breakCadenceIsEightToFifteenMessagesThenFourToTwelveMinutes() {
        Set<Integer> afters = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            int n = manual.nextBreakAfter(r);
            assertThat(n).isBetween(8, 15);
            afters.add(n);
            long b = manual.breakMillis(r);
            assertThat(b).isBetween(4 * 60_000L, 12 * 60_000L);
        }
        assertThat(afters).hasSize(8);   // the whole range is used
        int since = 0, breaks = 0, after = manual.nextBreakAfter(r);   // simulate 200 messages
        for (int m = 0; m < 200; m++) if (++since >= after) { breaks++; since = 0; after = manual.nextBreakAfter(r); }
        assertThat(breaks).isBetween(200 / 15 - 1, 200 / 8 + 1);
    }

    @Test
    void warmUpRampThenMaxDailyAndTheHourlyCap() {
        List<Integer> caps = List.of(1, 2, 3, 4, 5, 6, 7, 30).stream().map(manual::dailyCap).toList();
        assertThat(caps).containsExactly(10, 15, 20, 30, 40, 50, 80, 80);
        assertThat(HumanPolicy.warmupDay(null, LocalDate.of(2026, 1, 1))).isEqualTo(1);
        assertThat(HumanPolicy.warmupDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))).isEqualTo(1);
        assertThat(HumanPolicy.warmupDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4))).isEqualTo(4);
        assertThat(manual.dailyAllows(1, 9)).isTrue();
        assertThat(manual.dailyAllows(1, 10)).isFalse();
        assertThat(manual.hourlyAllows(24)).isTrue();
        assertThat(manual.hourlyAllows(25)).isFalse();
        // 100 messages: day 1 gives 10, day 2 15, 3 20, 4 30, 5 40 -> the fifth day is reached
        assertThat(manual.estimateDays(100, 1, 0)).isBetween(4.0, 5.0);
        assertThat(manual.estimateDays(10, 1, 0)).isBetween(0.1, 1.0);
    }

    @Test
    void activeWindowIsNineToTwentyOneInTheShopsOwnTime() {
        ZoneId cairo = ZoneId.of("Africa/Cairo");
        assertThat(manual.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 8, 59, 0, 0, cairo))).isFalse();
        assertThat(manual.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 9, 0, 0, 0, cairo))).isTrue();
        assertThat(manual.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 20, 59, 0, 0, cairo))).isTrue();
        assertThat(manual.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 21, 0, 0, 0, cairo))).isFalse();
        assertThat(manual.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 3, 0, 0, 0, cairo))).isFalse();
    }

    @Test
    void contentGuards() {
        assertThat(manual.contentProblem("Hi Mona, new arrivals today")).isNull();
        assertThat(manual.contentProblem("Offer at https://shop.example/a")).isNull();
        assertThat(manual.contentProblem("See https://a.example and https://b.example")).isEqualTo("TOO_MANY_LINKS");
        assertThat(manual.contentProblem("x".repeat(501))).isEqualTo("TOO_LONG");
        assertThat(manual.contentProblem("BUY NOW AMAZING DISCOUNT TODAY ONLY")).isEqualTo("ALL_CAPS");
        assertThat(manual.contentProblem("Sale!!!! come now")).isEqualTo("ALL_CAPS");
        assertThat(manual.contentProblem("Nice 🔥🔥🔥🔥 deal")).isEqualTo("EMOJI_REPEAT");
        assertThat(manual.contentProblem("Nice 🔥🔥🔥 deal 😊")).isNull();
        assertThat(manual.contentProblem(" ")).isEqualTo("EMPTY");
        assertThat(manual.contentProblem("عرض اليوم على كل المنتجات تعالى شوف")).isNull();
        // the same link twice is trimmed to one, other text is kept
        String stripped = HumanPolicy.stripRepeatedLinks("Look https://a.example now, again https://a.example please");
        assertThat(stripped).contains("https://a.example").doesNotContain("again https://a.example");
        assertThat(manual.contentProblem(stripped)).isNull();
    }

    @Test
    void wordingsNeedThreeVersionsRotateAndNeverRepeatInARow() {
        assertThat(manual.enoughVariants("a", List.of())).isFalse();
        assertThat(manual.enoughVariants("a", List.of("b"))).isFalse();
        assertThat(manual.enoughVariants("a", List.of("a", "b"))).isFalse();   // duplicates do not count
        assertThat(manual.enoughVariants("a", List.of("b", "c"))).isTrue();
        List<String> v = List.of("b", "c");
        String prev = null;
        for (int i = 0; i < 30; i++) {
            String t = HumanPolicy.rotate("a", v, i);
            assertThat(t).isNotEqualTo(prev);
            prev = t;
        }
        // tiny randomization never changes the words, and avoids the last hash when it can
        String text = "Big offer today at the shop 😊";
        Set<String> forms = new HashSet<>();
        for (int i = 0; i < 50; i++) forms.add(manual.vary(text, r, null));
        for (String f : forms) assertThat(f.replace(" ", "")).isEqualTo(text.replace(" ", ""));
        assertThat(forms.size()).isGreaterThan(1);
        String a = manual.vary(text, r, null);
        for (int i = 0; i < 20; i++) assertThat(HumanPolicy.hash(manual.vary(text, r, HumanPolicy.hash(a)))).isNotEqualTo(HumanPolicy.hash(a));
        assertThat(manual.vary("Link: https://x.example/a", r, null)).isEqualTo("Link: https://x.example/a");   // links are never touched
    }

    @Test
    void typingIsFortyMsPerCharPlusTwoToSixSeconds() {
        for (int i = 0; i < 200; i++) {
            int ms = manual.typingMillis(100, r, 0);
            assertThat(ms).isBetween(4000 + 2000, 4000 + 6000);
        }
    }

    // ---- WhatsApp own number: the owner's rules stay, the human rules are added ---------------------------------------------------

    @Test
    void whatsappGapStaysFiveToFifteenSecondsWithNoCapAtAll() {
        for (int i = 0; i < 2000; i++) assertThat(wa.gapMillis(r, 5, 15) / 1000.0).isBetween(5.0, 15.0);
        assertThat(wa.dailyCap(1)).isZero();
        assertThat(wa.dailyCap(50)).isZero();
        assertThat(wa.dailyAllows(1, 1_000_000)).isTrue();
        assertThat(wa.hourlyAllows(1_000_000)).isTrue();
        assertThat(wa.inActiveWindow(ZonedDateTime.of(2026, 3, 3, 3, 0, 0, 0, ZoneId.of("Africa/Cairo")))).isTrue();   // no active-hours window by default
        assertThat(wa.estimateDays(5000, 1, 0)).isLessThan(2.0);   // the credit, not a cap, is the limit
    }

    @Test
    void whatsappBreaksAreOneToThreeMinutesEveryFortyToEightyMessages() {
        for (int i = 0; i < 300; i++) {
            assertThat(wa.nextBreakAfter(r)).isBetween(40, 80);
            assertThat(wa.breakMillis(r)).isBetween(60_000L, 180_000L);
        }
    }

    @Test
    void whatsappTypingIsCappedAtFourSecondsAndByTheGap() {
        assertThat(wa.typingMillis(50, r, 0)).isEqualTo(2000);        // 40 ms per character
        assertThat(wa.typingMillis(900, r, 0)).isEqualTo(4000);       // capped
        assertThat(wa.typingMillis(900, r, 3000)).isEqualTo(3000);    // never longer than the gap
    }

    @Test
    void whatsappRotationContentGuardsAndNoRepeat() {
        assertThat(wa.enoughVariants("a", List.of())).isTrue();   // wordings are optional on WhatsApp
        String prev = null;
        for (int i = 0; i < 12; i++) { String t = HumanPolicy.rotate("a", List.of("b", "c"), i); assertThat(t).isNotEqualTo(prev); prev = t; }
        assertThat(wa.contentProblem("Hello! see https://a.example and https://b.example")).isEqualTo("TOO_MANY_LINKS");
        assertThat(wa.contentProblem("y".repeat(1001))).isEqualTo("TOO_LONG");
        assertThat(wa.contentProblem("y".repeat(900))).isNull();   // longer messages are fine on WhatsApp
    }
}

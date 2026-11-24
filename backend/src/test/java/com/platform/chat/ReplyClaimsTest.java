package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Facts the checks missed in the 2026-11-24 runs: an option price written with English units («64GB + 2TB»), and a claim about the food or the goods («حلال», «مستورد») that the shop never wrote. */
class ReplyClaimsTest {
    static FactCheck.Variant v(String ram, String disk, double price) { return new FactCheck.Variant(Map.of("الرامات", ram, "التخزين", disk), price, 0, 3); }

    static final FactCheck.Product CREATOR = new FactCheck.Product("creator", "لاب توب زينترا كرييتور 16", 0,
            List.of(v("32 جيجا", "1 تيرا SSD", 64999), v("32 جيجا", "2 تيرا SSD", 68999), v("64 جيجا", "1 تيرا SSD", 70999), v("64 جيجا", "2 تيرا SSD", 74999)));

    static final FactCheck.Shop SHOP = new FactCheck.Shop() {
        public List<FactCheck.Named> names() { return List.of(new FactCheck.Named("creator", "لاب توب زينترا كرييتور 16"), new FactCheck.Named("creator", "Zentra Creator 16")); }
        public FactCheck.Product product(String slug) { return "creator".equals(slug) ? CREATOR : null; }
        public List<FactCheck.Named> searchAll(List<String> words) { return List.of(); }
        public Set<String> served() { return Set.of(); }
        public List<int[]> hours() { return List.of(); }
    };

    @Test
    void anOptionPriceWithEnglishUnitsIsChecked() {
        String reply = "The Zentra Creator 16 comes as 32GB RAM + 1TB SSD at 64,999 EGP, and the more powerful 64GB + 2TB one is 70,999 EGP.";
        var kinds = FactCheck.check("Hi, a laptop for video editing?", reply, SHOP, List.of("creator")).stream().map(FactCheck.Problem::kind).toList();
        assertThat(kinds).contains("OPTION_PRICE");
        String right = "The Zentra Creator 16 comes as 32GB RAM + 1TB SSD at 64,999 EGP, and the more powerful 64GB + 2TB one is 74,999 EGP.";
        assertThat(FactCheck.check("Hi, a laptop for video editing?", right, SHOP, List.of("creator")).stream().map(FactCheck.Problem::kind).toList()).doesNotContain("OPTION_PRICE");
    }

    @Test
    void numbersRunIntoTheirUnitAreTheShopsOwnWords() {
        var t = FactCheck.tokens(FactCheck.light("64GB و 2 TB و 65inch"), 0, 40).stream().map(FactCheck.Tok::word).toList();
        assertThat(t).containsSubsequence("64", "جيجا").containsSubsequence("2", "تيرا").containsSubsequence("65", "بوصه");
    }

    static FactCheck.Shop writing(String shopText) {
        return new FactCheck.Shop() {
            public List<FactCheck.Named> names() { return List.of(new FactCheck.Named("shawarma", "ساندوتش شاورما لحمة")); }
            public FactCheck.Product product(String slug) { return null; }
            public List<FactCheck.Named> searchAll(List<String> words) { return List.of(); }
            public Set<String> served() { return Set.of(); }
            public List<int[]> hours() { return List.of(); }
            public Boolean says(List<String> stems) { String t = FactCheck.light(shopText).replace('ة', 'ه').toLowerCase(); return stems.stream().anyMatch(x -> t.contains(x.toLowerCase())); }
        };
    }

    @Test
    void aHalalClaimTheShopNeverWroteIsTakenOut() {
        String reply = "أيوه يا فندم، اللحمة عندنا بتلو بلدي حلال ومفروقة من مصادر موثوقة. تحب أجهزلك ساندوتش شاورما لحمة؟";
        var p = FactCheck.check("اللحمة حلال؟", reply, writing("بيت الشاورما مطعم تيك أواي في مدينة نصر. الشاورما على السيخ كل يوم."), List.of()).stream()
                .filter(x -> x.kind().equals("SPEC_UNKNOWN")).findFirst().orElseThrow();
        assertThat(p.fixed()).doesNotContain("حلال").contains("تحب أجهزلك ساندوتش شاورما لحمة؟").contains("أسأل المحل");
    }

    @Test
    void aClaimTheShopWroteAnHonestNoAndAQuestionAreLeftAlone() {
        var shop = writing("كل اللحوم عندنا حلال ومذبوحة على الطريقة الإسلامية.");
        assertThat(FactCheck.check("اللحمة حلال؟", "أيوه يا فندم، اللحمة عندنا حلال 100%.", shop, List.of())).noneMatch(x -> x.kind().equals("SPEC_UNKNOWN"));
        var none = writing("مطعم تيك أواي.");
        assertThat(FactCheck.check("اللحمة حلال؟", "شهادة الحلال مش مذكورة في بياناتنا، وسألت فريق المحل وهيردوا عليك هنا.", none, List.of())).noneMatch(x -> x.kind().equals("SPEC_UNKNOWN"));
        assertThat(FactCheck.check("عايز ساندوتش", "تحب اللحمة تكون حلال ولا مش فارقة؟", none, List.of())).noneMatch(x -> x.kind().equals("SPEC_UNKNOWN"));
    }

    @Test
    void otherClaimsAboutTheGoodsAreCheckedTheSameWay() {
        var none = writing("قمصان قطن بأسعار حلوة.");
        assertThat(FactCheck.check("القميص ده خامته ايه؟", "القميص ده قطن مستورد صناعة تركي يا فندم.", none, List.of())).anyMatch(x -> x.kind().equals("SPEC_UNKNOWN"));
        assertThat(FactCheck.check("القميص ده خامته ايه؟", "القميص ده قطن مستورد صناعة تركي يا فندم.", writing("قميص قطن تركي مستورد"), List.of())).noneMatch(x -> x.kind().equals("SPEC_UNKNOWN"));
    }

    @Test
    void theWaysTheModelsSayTheTeamWasToldAreRecognised() {
        for (String said : List.of("ورفعت المشكلة لفريق المحل حالاً هما هيجاوبوك في نفس الشات ده", "سجلت الشكوى لفريق المتجر وهيردوا عليك هنا", "حولت الشكوى لفريق المحل وهيردوا عليك",
                "سألت فريق المحل وهايجيني ردهم", "I've asked the shop team to confirm it for you; their answer will appear right here.", "I let the team know."))
            assertThat(ChatService.TEAM_TOLD.matcher(said).find()).as(said).isTrue();
        assertThat(ChatService.TEAM_TOLD.matcher("تحب أوريك صور الموبايلات؟").find()).isFalse();
    }
}

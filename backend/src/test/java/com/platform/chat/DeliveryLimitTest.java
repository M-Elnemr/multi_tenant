package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A variant the shop delivers only to some governorates (tech-zone, the 2026-11-24 A/B: «فيستا 4K سمارت ٦٥ بوصة ... بيتوصلوا للإسكندرية» while TVs of 50 inches and up go by the shop's
 * truck to Cairo and Giza only), and the reply text the model wrote next to a background note (flag_unanswered) that used to be lost.
 */
class DeliveryLimitTest {
    static FactCheck.Variant v(String size, double price) { return new FactCheck.Variant(Map.of("المقاس", size), price, 0, 5); }

    static final FactCheck.Product TV = new FactCheck.Product("tv4k", "تلفزيون فيستا 4K سمارت", 0, List.of(v("43 بوصة", 15999), v("50 بوصة", 19999), v("65 بوصة", 27499)));

    static final FactCheck.Shop SHOP = new FactCheck.Shop() {
        public List<FactCheck.Named> names() { return List.of(new FactCheck.Named("tv4k", "تلفزيون فيستا 4K سمارت")); }
        public FactCheck.Product product(String slug) { return "tv4k".equals(slug) ? TV : null; }
        public List<FactCheck.Named> searchAll(List<String> words) { return List.of(); }
        public Set<String> served() { return Set.of("CAI", "GIZ", "ALX"); }
        public List<int[]> hours() { return List.of(); }
        public List<FactCheck.Limit> limits(String slug) {
            return "tv4k".equals(slug) ? List.of(new FactCheck.Limit(Map.of("المقاس", "50 بوصة"), Set.of("CAI", "GIZ")), new FactCheck.Limit(Map.of("المقاس", "65 بوصة"), Set.of("CAI", "GIZ"))) : List.of();
        }
    };

    static List<FactCheck.Problem> check(String reply) { return FactCheck.check("عايز تلفزيون ٦٥ بوصة واتوصل اسكندرية", reply, SHOP, List.of("tv4k")); }

    @Test
    void deliveryOfALimitedSizeToAnotherGovernorateIsCaughtAndCorrected() {
        String reply = "عندنا فيستا 4K سمارت ٦٥ بوصة بـ ٢٧٤٩٩ جنيه. الاتنين بيتوصلوا للإسكندرية في ٢-٣ أيام. تحب أحجزهولك؟";
        var p = check(reply).stream().filter(x -> x.kind().equals("AREA")).findFirst().orElseThrow();
        assertThat(p.note()).contains("ONLY to").contains("Alexandria");
        assertThat(p.fixed()).contains("بيتوصل لـالقاهرة والجيزة بس").doesNotContain("بيتوصلوا للإسكندرية").contains("تحب أحجزهولك؟");
    }

    @Test
    void theSizeThatGoesEverywhereAndASentenceThatSaysNoAreFine() {
        assertThat(check("فيستا 4K سمارت ٤٣ بوصة بـ ١٥٩٩٩ جنيه، وبنوصله للإسكندرية في ٢-٣ أيام.")).noneMatch(x -> x.kind().equals("AREA"));
        assertThat(check("للأسف الـ ٦٥ بوصة مش بنوصله للإسكندرية، بيتوصل للقاهرة والجيزة بس.")).noneMatch(x -> x.kind().equals("AREA"));
        assertThat(check("الـ ٦٥ بوصة بـ ٢٧٤٩٩ وبيتوصل للجيزة في يوم.")).noneMatch(x -> x.kind().equals("AREA"));
    }

    @Test
    void aSentenceAboutTheSizeThatGoesEverywhereIsLeftAloneEvenWhenTheReplyNamesTheLimitedOne() {
        String reply = "للأسف الـ ٦٥ بوصة مش بنوصله للإسكندرية، بيتوصل القاهرة والجيزة بس. لو حابب، فيستا 4K سمارت ٤٣ بوصة بـ ١٤٩٩٩ وبنوصله للإسكندرية في ٢-٣ أيام.";
        assertThat(check(reply)).noneMatch(x -> x.kind().equals("AREA"));
        // a sentence naming no size, in a reply only about the 65-inch, is still about it
        assertThat(check("فيستا 4K سمارت ٦٥ بوصة بـ ٢٧٤٩٩. وبيوصل للإسكندرية في ٢-٣ أيام.")).anyMatch(x -> x.kind().equals("AREA"));
    }

    @Test
    void aPriceThatContainsTheNumberIsNotTheSize() {
        assertThat(FactCheck.saysValue(FactCheck.light("السعر 16500 جنيه"), "65 بوصة")).isFalse();
        assertThat(FactCheck.saysValue(FactCheck.light("الشاشة ٦٥ بوصة"), "65 بوصة")).isTrue();
        assertThat(FactCheck.saysValue(FactCheck.light("مقاس 65"), "65 بوصة")).isTrue();
    }

    @Test
    void theModelIsToldWhichSizesAreLimited() {
        String some = ChatService.deliveryOnlyText("¤المقاس=43 بوصة|التركيب=بدون§CAI,GIZ¤المقاس=50 بوصة|التركيب=بدون§CAI,GIZ¤المقاس=65 بوصة|التركيب=بدون§CAI,GIZ¤المقاس=65 بوصة|التركيب=حيطة");
        assertThat(some).contains("«المقاس: 50 بوصة، 65 بوصة»").contains("DELIVERED ONLY TO Cairo").contains("Giza").contains("other options go to the shop's normal areas");
        String all = ChatService.deliveryOnlyText("CAI,GIZ¤المقاس=55 بوصة§CAI,GIZ¤المقاس=65 بوصة");
        assertThat(all).startsWith("DELIVERED ONLY TO").contains("this product");
        assertThat(ChatService.deliveryOnlyText("¤المقاس=43 بوصة§¤المقاس=50 بوصة")).isNull();
        assertThat(ChatService.deliveryOnlyText(null)).isNull();
    }

    @Test
    void theAnswerWrittenNextToAHandOffIsKeptInFrontOfTheFinalWords() {
        String said = "The Creator 16's spec sheet doesn't list the operating system, so I've asked the shop team; their answer will appear here.";
        assertThat(ChatService.withSaid(List.of(said), "In the meantime: which one are you leaning toward, the 32GB or the 64GB?"))
                .startsWith(said).endsWith("the 32GB or the 64GB?");
        // the final words already say it: not twice
        assertThat(ChatService.withSaid(List.of("I asked the shop team about the warranty."), "I asked the shop team about the warranty; they will answer here. Which colour?"))
                .isEqualTo("I asked the shop team about the warranty; they will answer here. Which colour?");
        assertThat(ChatService.withSaid(List.of(said), "")).isEqualTo(said);
        // two full answers (the cold-food complaint): only the final one, never the same request twice
        String first = "أنا آسف جداً يا فندم على ده 🙏 لو الطلب وصل لسه ضمن ساعة، سياسة المحل بتقول إننا نبدله أو نرجع تمنه. تحب تديني رقم الطلب عشان أتابعه فوراً؟ وهرفع الموضوع للفريق.";
        String fin = "رفعت الشكوى للفريق وهيردوا عليك هنا في نفس الشات في أقرب وقت. لو ممكن تبعتلي رقم الطلب ورقم الواتساب المسجل عليه، أقدر أتابع حالته فوراً.";
        assertThat(ChatService.withSaid(List.of(first), fin)).isEqualTo(fin);
        assertThat(ChatService.withSaid(List.of(), "ok")).isEqualTo("ok");
    }
}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The fact checks on the real demo shops' data (fixtures exported from beit-elshawarma and nile-style): every slip seen in the test chats is caught, and the correct replies from the
 * same chats are left alone.
 */
class FactCheckTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A shop read from a fixture; search works like the storefront's: every word somewhere in the product's text. */
    static FactCheck.Shop fixture(String slug) {
        try {
            JsonNode root = JSON.readTree(FactCheckTest.class.getResourceAsStream("/factcheck/" + slug + ".json"));
            List<FactCheck.Named> names = new ArrayList<>();
            Map<String, FactCheck.Product> bySlug = new LinkedHashMap<>();
            Map<String, String> search = new LinkedHashMap<>();
            for (JsonNode p : root.path("products")) {
                List<FactCheck.Variant> vs = new ArrayList<>();
                for (JsonNode v : p.path("variants")) {
                    Map<String, String> o = new LinkedHashMap<>();
                    v.path("options").fields().forEachRemaining(e -> o.put(e.getKey(), e.getValue().asText()));
                    vs.add(new FactCheck.Variant(o, v.path("price").asDouble(), v.path("before").asDouble(), v.path("available").asInt()));
                }
                String s = p.path("slug").asText();
                names.add(new FactCheck.Named(s, p.path("name").asText()));
                bySlug.put(s, new FactCheck.Product(s, p.path("name").asText(), p.path("before").asDouble(), vs));
                search.put(s, FastAnswers.norm(p.path("search").asText()));
            }
            Set<String> served = new TreeSet<>();
            for (JsonNode c : root.path("served")) served.add(c.asText());
            List<int[]> hours = new ArrayList<>();
            for (JsonNode h : root.path("hours")) hours.add(new int[]{h.get(0).asInt(), h.get(1).asInt()});
            String pickup = root.path("pickup").isNull() ? null : root.path("pickup").asText();
            Set<String> tags = new java.util.HashSet<>();
            for (JsonNode p : root.path("products")) for (JsonNode t : p.path("tags")) for (String w : FastAnswers.norm(t.asText()).split("\\s+")) if (w.length() >= 3) tags.add(FactCheck.bare(w));
            JsonNode zones = root.path("zones");
            return new FactCheck.Shop() {
                public List<FactCheck.Named> names() { return names; }
                public FactCheck.Product product(String s) { return bySlug.get(s); }
                public List<FactCheck.Named> searchAll(List<String> words) {
                    List<FactCheck.Named> out = new ArrayList<>();
                    for (var n : names) if (words.stream().allMatch(w -> search.get(n.slug()).contains(FastAnswers.norm(w)))) out.add(n);
                    return out;
                }
                public Set<String> served() { return served; }
                public String zoneLine(String code, boolean arabic) {
                    JsonNode z = zones.path(code);
                    return z.isMissingNode() ? null : (arabic ? "أيوه بنوصل، التوصيل " + z.path("fee").asInt() + " جنيه" : "Yes, we deliver there: " + z.path("fee").asInt() + " EGP");
                }
                public List<int[]> hours() { return hours; }
                public String pickup() { return pickup; }
                public Set<String> tags() { return tags; }
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final FactCheck.Shop REST = fixture("beit-elshawarma");
    static final FactCheck.Shop FASH = fixture("nile-style");

    private static List<String> kinds(FactCheck.Shop shop, String customer, String reply) {
        return FactCheck.check(customer, reply, shop, List.of()).stream().map(FactCheck.Problem::kind).toList();
    }

    private static String slugOf(FactCheck.Shop shop, String name) {
        return shop.names().stream().filter(n -> n.name().equals(name)).map(FactCheck.Named::slug).findFirst().orElseThrow();
    }

    // ---- the slips seen in the test chats -----------------------------------------------------------------------------

    @Test
    void largeSandwichPricedAsTheSuper() {
        String reply = "عندنا ساندوتش شاورما فراخ كبير بـ ١٤٠ جنيه، والخبز صاج أو عيش سوري أو فينو.";
        var p = FactCheck.check("عايز ساندوتش شاورما فراخ كبير", reply, REST, List.of());
        assertThat(p).extracting(FactCheck.Problem::kind).contains("OPTION_PRICE");
        assertThat(FactCheck.fix("عايز ساندوتش شاورما فراخ كبير", reply, REST, List.of())).contains("١١٠").doesNotContain("١٤٠");
    }

    @Test
    void englishLargePricedAsTheSuperWhenTheProductIsKnown() {
        String reply = "Great choice — the large one is 140 EGP. Saj, Syrian bread or fino?";
        var p = FactCheck.check("Hi, I want a chicken shawarma sandwich, large", reply, REST, List.of(slugOf(REST, "ساندوتش شاورما فراخ")));
        assertThat(p).extracting(FactCheck.Problem::kind).contains("OPTION_PRICE");
    }

    @Test
    void debugOnce() {
        if (System.getProperty("factcheck.corpus", "").isEmpty()) return;
        for (var p : FactCheck.check("عايز طقم كامل لخروجة: قميص وبنطلون وجزمة، ميزانيتي ٢٥٠٠", "حقك عليا يا فندم، للأسف عندنا أحذية للولاد أو حريمي بس مش رجالي، فمش هقدر أظبطلك الجزمة مع الطقم.", FASH, List.of())) System.out.println("DBG " + p);
    }

    @Test
    void sizeListWithRightPricesIsLeftAlone() {
        assertThat(kinds(REST, "عايز ساندوتش شاورما فراخ", "عندنا ساندوتش شاورما فراخ بالثومية والمخلل، متاح بـ ٣ أحجام:\n- عادي ٨٥ جنيه\n- كبير ١١٠ جنيه\n- سوبر ١٤٠ جنيه\nتحب أنهي حجم؟"))
                .doesNotContain("OPTION_PRICE");
        assertThat(kinds(REST, "عايز ساندوتش شاورما فراخ", "عندنا ساندوتش شاورما فراخ:\n- عادي ٨٥ جنيه\n- كبير ١٣٥ جنيه\n- سوبر ١٤٠ جنيه")).contains("OPTION_PRICE");
    }

    @Test
    void priceBeforeItsOptionIsReadRight() {
        assertThat(kinds(REST, "حاجة فراخ", "- استربس فراخ: يبدأ من 90 جنيه لـ٣ قطع، ويوصل لـ240 جنيه لـ٩ قطع (عادي أو حار) 🍗")).doesNotContain("OPTION_PRICE");
    }

    @Test
    void vegetarianDeniedWhenTheMenuHasFalafel() {
        String reply = "يا فندم، معاك حق، بس للأسف القائمة بتاعتنا فيها شاورما سوري، بروستد ومشويات على الفحم بس ومفيش خيارات نباتية حالياً.";
        var p = FactCheck.check("انا نباتي في حاجة اكلها؟", reply, REST, List.of());
        assertThat(p).extracting(FactCheck.Problem::kind).contains("FALSE_DENIAL");
        assertThat(p.stream().filter(x -> x.kind().equals("FALSE_DENIAL")).findFirst().orElseThrow().note()).contains("حمص");
    }

    @Test
    void menShoesDeniedWhenTheShopHasMenBoots() {
        assertThat(kinds(FASH, "عايز طقم كامل لخروجة: قميص وبنطلون وجزمة، ميزانيتي ٢٥٠٠", "حقك عليا يا فندم، للأسف عندنا أحذية للولاد أو حريمي بس مش رجالي، فمش هقدر أظبطلك الجزمة مع الطقم."))
                .contains("FALSE_DENIAL");
    }

    @Test
    void oliveDeniedForThePufferThatHasOliveInEverySize() {
        // the men's puffer comes in olive in every size, XXL included (6 in stock): «not in olive» is wrong
        var p = FactCheck.check("الجاكيت البافر الرجالي زيتي XXL متاح؟", "عذراً يا فندم، عندنا جاكيت بافر رجالي بس مش باللون الزيتي ولا المقاس ده. عندنا أسود وكحلي والمقاسات S, M, L, XL. تحب أوريكهم؟", FASH, List.of());
        assertThat(p).extracting(FactCheck.Problem::kind).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "الجاكيت البافر الرجالي زيتي XXL متاح؟", "للأسف الجاكيت البافر الشتوي الرجالي الزيتي مش متوفر في XXL، متاح في S و M بس.")).contains("OPTION_STOCK");
    }

    @Test
    void theRightStockAnswerIsLeftAlone() {
        assertThat(kinds(FASH, "الجاكيت البافر الرجالي زيتي XXL متاح؟", "أيوه يا فندم، الجاكيت البافر الشتوي الرجالي متاح بالزيتي مقاس XXL (فيه ٦ قطع). تحب أحجزهولك؟")).doesNotContain("OPTION_STOCK");
        // the kids' puffer is sold out in red 10 and 12
        assertThat(kinds(FASH, "جاكيت بافر ولادي احمر ١٠ سنة", "للأسف جاكيت بافر ولادي الأحمر خلص في مقاس 10 سنة، متاح الأحمر 4 و6 و8 سنة.")).doesNotContain("OPTION_STOCK");
    }

    @Test
    void alexandriaDeliveryPromisedByARestaurantThatOnlyServesCairoAndGiza() {
        String reply = "بنوصل الإسكندرية عن طريق التوصيل القياسي بتكلفة 50 جنيه. حضرتك عايز تطلب حاجة دلوقتي؟";
        assertThat(kinds(REST, "بتوصلوا اسكندرية؟", reply)).contains("AREA");
        assertThat(FactCheck.fix("بتوصلوا اسكندرية؟", reply, REST, List.of())).contains("القاهرة").doesNotContain("50 جنيه");
    }

    @Test
    void theRightNoToAlexandriaIsLeftAlone() {
        assertThat(kinds(REST, "بتوصلوا اسكندرية؟", "للأسف لأ يا فندم، التوصيل عندنا داخل القاهرة والجيزة بس (مدينة نصر، التجمع، المعادي، الدقي... إلخ). تقدر تستلم من الفرع في مدينة نصر."))
                .doesNotContain("AREA");
    }

    @Test
    void wrongOpeningTimesAreCaught() {
        assertThat(kinds(REST, "عايز وجبة شاورما فراخ", "تمام، بيتحضر حالاً ما المحل يفتح (12 بالليل). تحب توصيل؟")).contains("HOURS");
        assertThat(kinds(REST, "كبير", "معلش يا فندم، الفرع دلوقتي قفل (ساعة الشغل لحد نص الليل بس مش شغالين النهاردة).")).contains("HOURS");
        assertThat(kinds(REST, "فاتحين لحد امتى؟", "نفتح من ١٢ الضهر لحد ٢ بالليل يومياً.")).doesNotContain("HOURS");
        assertThat(kinds(REST, "فاتحين لحد امتى؟", "المطعم مفتوح من ١٢ ظهراً لحد ٢ فجراً كل يوم. 🌙")).doesNotContain("HOURS");
    }

    @Test
    void aFullPriceAbayaListedAsOnSale() {
        String reply = "عندنا عبايات عليها تخفيضات حاليًا:\n\n- عباية مطرزة: سعرها 1400 جنيه بدل 1650. متوفرة باللون الأسود والكحلي.\n- عباية كريب سادة: سعرها 1200 جنيه. متوفرة بالألوان الأسود، الكحلي، والبيج.\n\nحضرتك تحب تفصل في نوع معين؟";
        assertThat(kinds(FASH, "ايه اللي عليه خصم دلوقتي؟", reply)).contains("SALE");
        assertThat(FactCheck.fix("ايه اللي عليه خصم دلوقتي؟", reply, FASH, List.of())).contains("عباية مطرزة").doesNotContain("كريب");
    }

    @Test
    void aGreetingAnswerToAClearOrder() {
        assertThat(kinds(REST, "عايز ساندوتش شاورما لحمة عادي صاج بس من غير بصل", "سعيدة جداً، إزاي أقدر أساعدك؟")).contains("OFF_TOPIC");
        assertThat(kinds(REST, "أهلا", "أهلاً بيك! إزاي أقدر أساعدك؟")).doesNotContain("OFF_TOPIC");
        assertThat(kinds(REST, "شكرا جدا", "العفو يا فندم 🌸 إزاي أقدر أساعدك تاني؟")).doesNotContain("OFF_TOPIC");
    }

    @Test
    void aSoldOutColourAndSizeOfferedTogetherIsCaught() {
        // the kids' puffer has red, but red 10 is sold out; the gold evening shoe has 38 sold out
        assertThat(kinds(FASH, "جاكيت بافر ولادي احمر مقاس ١٠ سنين", "اللون الأحمر والمقاس 10 سنين متوفرين بالظبط يا فندم. الجاكيت بافر ولادي أحمر مقاس 10 سنة سعره 890 جنيه.")).contains("OPTION_STOCK");
        assertThat(FactCheck.check("جاكيت بافر ولادي احمر مقاس ١٠ سنين", "ده شكله، مبطن وشتوي ومناسب للبرد 😊 تحب أحجزهولك؟", FASH, List.of(slugOf(FASH, "جاكيت بافر ولادي"))))
                .extracting(FactCheck.Problem::kind).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "حذاء السواريه اللامع ذهبي مقاس ٣٨ موجود؟", "أيوه موجود يا فندم ✅ حذاء سواريه لامع ذهبي مقاس ٣٨ متاح، سعره 1150 جنيه.")).contains("OPTION_STOCK");
        // the tools returned several products and the reply names none: the one the customer named counts
        assertThat(FactCheck.check("جاكيت بافر ولادي احمر مقاس ١٠ سنين", "ده الجاكيت، ٨٩٠ جنيه، واللون الأحمر مقاس ١٠ سنين موجود. تحب أحجزهولك؟", FASH,
                List.of(slugOf(FASH, "جاكيت بافر ولادي"), slugOf(FASH, "جاكيت بافر بناتي")))).extracting(FactCheck.Problem::kind).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "تيشيرت القطن السادة الرجالي متاح بكل المقاسات؟", "أيوة متاح يا فندم ✅ تيشيرت قطن سادة رجالي بـ ٢٩٠ جنيه، والمقاسات كلها موجودة من S لحد XXL.")).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "عايز جينز سليم فيت رجالي كحلي L", "للأسف الجينز غير متاح عندنا حالياً، وأقرب حاجة بنطلون كحلي.")).contains("FALSE_DENIAL");
        // red 8 is in stock: fine
        assertThat(kinds(FASH, "جاكيت بافر ولادي احمر مقاس ٨ سنين", "متاح يا فندم، جاكيت بافر ولادي أحمر مقاس 8 سنة بـ 890 جنيه. تحب أحجزهولك؟")).doesNotContain("OPTION_STOCK");
    }

    @Test
    void aWrongStockCountAndAWrongAllSizesAreCaught() {
        assertThat(kinds(FASH, "الجاكيت البافر الشتوي الرجالي زيتي XXL موجود؟", "موجود يا فندم! جاكيت البافر الشتوي الرجالي باللون الزيتي ومقاس XXL متوفر (16 قطعة في المخزن).")).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "تيشيرت القطن السادة الرجالي متاح بكل المقاسات؟", "أيوة يا فندم، تيشيرت قطن سادة رجالي بسعر ٢٩٠ جنيه متاح بكل المقاسات من S لحد XXL.")).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "تيشيرت القطن السادة الرجالي متاح بكل المقاسات؟", "تيشيرت قطن سادة رجالي متاح بكل المقاسات يا فندم (S, M, L, XL, XXL) وبالألوان: أسود، أبيض، رمادي، كحلي، زيتي.")).contains("OPTION_STOCK");
    }

    @Test
    void dessertsForAVegetarianAreTheWrongKind() {
        assertThat(kinds(REST, "عندكم أكل نباتي؟", "عندنا حلويات نباتية، بسبوسة بالقشطة بـ 50 جنيه، تشيز كيك فراولة بـ 85 جنيه، براونيز بالشوكولاتة بـ 60 جنيه. تحب نجهزلك حاجة منها؟")).contains("WRONG_KIND");
        assertThat(kinds(REST, "عندكم أكل نباتي؟", "أيوة عندنا: ساندوتش فلافل سوري ٣٥ جنيه، حمص بالطحينة ٤٠ جنيه، سلطة فتوش ٥٥ جنيه.")).doesNotContain("WRONG_KIND");
        assertThat(kinds(REST, "عايز ساندوتش شاورما فراخ", "عندنا ساندوتش شاورما فراخ: عادي ٨٥، كبير ١١٠، سوبر ١٤٠.")).doesNotContain("WRONG_KIND");
    }

    // ---- every reply of the recorded test chats: what the checks say ------------------------------------------------

    /** Run with -Dfactcheck.corpus=<transcript.txt>,<shop slug>[;...] to print what the checks say about a recorded test run. */
    @Test
    void corpus() throws Exception {
        String spec = System.getProperty("factcheck.corpus");
        if (spec == null || spec.isBlank()) return;
        for (String one : spec.split(";")) {
            String[] parts = one.split(",");
            FactCheck.Shop shop = fixture(parts[1]);
            String customer = "";
            String id = "";
            StringBuilder reply = null;
            List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(parts[0]));
            lines.add("### end");
            for (String line : lines) {
                boolean boundary = line.startsWith("### ") || line.startsWith("👤 ") || line.startsWith("🤖 ");
                if (boundary && reply != null) {
                    String r = reply.toString().trim();
                    List<String> touched = new ArrayList<>();
                    var m = java.util.regex.Pattern.compile("^\\(\\d+(?:\\.\\d+)?s\\)\\s*(?:\\[cards: ([^\\]]*)\\])?\\s*").matcher(r);
                    if (m.find()) {
                        if (m.group(1) != null) for (String n : m.group(1).split(",\\s*")) shop.names().stream().filter(x -> x.name().equals(n.trim())).findFirst().ifPresent(x -> touched.add(x.slug()));
                        r = r.substring(m.end());
                    }
                    for (var p : FactCheck.check(customer, r, shop, touched))
                        System.out.println("FLAG " + parts[1] + " " + id + " " + p.kind() + " :: " + p.note() + "\n      reply: " + r.replace("\n", " ⏎ ") + (p.fixed() != null ? "\n      fixed: " + p.fixed().replace("\n", " ⏎ ") : ""));
                    reply = null;
                }
                if (line.startsWith("### ")) id = line.substring(4).trim();
                else if (line.startsWith("👤 ")) customer = line.substring(2).trim();
                else if (line.startsWith("🤖 ")) reply = new StringBuilder(line.substring(2));
                else if (reply != null) reply.append("\n").append(line);
            }
        }
    }
}

package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("factcheck.corpus"));
        if (!java.nio.file.Files.exists(f)) return;
        try {
            // a probe file: «shop|customer|reply|touched» per line
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] c = line.split("\\|", -1);
                if (c.length < 3) continue;
                FactCheck.Shop sh = fixture(c[0]);
                String reply = c[2].replace("⏎", "\n");
                List<String> touched = c.length > 3 && !c[3].isBlank() ? List.of(c[3].split(",")) : List.of();
                System.out.println("DBG mentions c=" + FactCheck.mentions(c[1], sh.names()) + " r=" + FactCheck.mentions(reply, sh.names()));
                System.out.println("DBG " + c[1] + "\n    -> " + FactCheck.check(c[1], reply, sh, touched).stream().map(x -> x.kind() + " :: " + x.note() + (x.fixed() == null ? "" : " || FIX: " + x.fixed().replace("\n", " ⏎ "))).toList());
            }
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    // ---- round 3 of the exam: slips no check saw, and right replies that must stay ---------------------------------------

    static final FactCheck.Shop TECH = fixture("tech-zone");

    @Test
    void aProductTheCustomerNamedIsNeverDeniedWhenItIsInStock() {
        var p = FactCheck.check("عايز ويب كام زينترا 1080p أسود", "للأسف مفيش ويب كام زينترا 1080p عندنا دلوقتي في المخزون. تحب أشوفلك على ماركات تانية متاحة؟", TECH, List.of());
        assertThat(p).extracting(FactCheck.Problem::kind).contains("FALSE_DENIAL");
        assertThat(FactCheck.fix("عايز ويب كام زينترا 1080p أسود", "للأسف مفيش ويب كام زينترا 1080p عندنا دلوقتي في المخزون.", TECH, List.of())).contains("799").doesNotContain("مفيش");
        assertThat(kinds(REST, "عايز مياه معدنية كبيرة ١.٥ لتر", "عذرًا يا فندم، عندنا مش بنبيع مياه معدنية لأننا متخصصين في الشاورما والمشويات.")).contains("FALSE_DENIAL");
        assertThat(kinds(REST, "عايز تشيز كيك فراولة", "عذراً يا فندم، عندنا تشيز كيك فراولة. عندنا شاورما وبروستد فقط. تحب نجهزلك طلب؟")).contains("FALSE_DENIAL");
        // a quantity, a limit or «only white» is not a denial of the product
        assertThat(kinds(TECH, "عايز 18 قطعة من ويب كام زينترا 1080p أسود", "للأسف الموجود حالياً من ويب كام زينترا 1080p أسود 8 قطع بس، مفيش 18. تحب أحجزلك الثمانية؟")).doesNotContain("FALSE_DENIAL", "STOCK_QUANTITY");
        assertThat(kinds(REST, "عايز 302 قطعة ناجتس فراخ ٩ قطع", "بالنسبة للناجتس، عندنا صنف ناجتس فراخ، بس مفيش حد أقصى للكمية في الفاتورة الواحدة.")).doesNotContain("FALSE_DENIAL");
    }

    @Test
    void theSlipsOfExamRoundFourAreCaught() {
        // a product named exactly is denied «on its own», or «not found» after a search
        assertThat(kinds(REST, "عايز اعرف سعر ثومية كبير", "عندنا شاورما وسندوتحات ومشويات، لكن مش بنبيع ثومية لوحدها. عايز تطلب ساندوتش شاورما أو أكلة من القائمة؟")).contains("FALSE_DENIAL");
        assertThat(kinds(FASH, "حزام نسائي رفيع أسود متاح؟", "للأسف، بحثت في المخزون الحالي ومش لقيت حزام نسائي رفيع أسود مطابق للوصف ده. عندنا تشكيلة متنوعة من الحزام والاكسسوارات، تحب أشوفلك بدائل متاحة؟")).contains("OPTION_STOCK");
        assertThat(kinds(TECH, "عايز لاب توب زينترا بوك 14 أوبن بوكس فضي 8 جيجا 512 جيجا SSD", "تمام يا فندم، شوفت اللاب توب: لاب توب زينترا بوك 14 مفيهوش نسخة أوبن بوكس، الموجود منه جديد ✅\n\n- فضي، 8 جيجا رام، 512 SSD بسعر 24,999 جنيه (متوفر)")).contains("FALSE_DENIAL");
        // the customer's last message names nothing: the denial is read for the product of the chat
        assertThat(FactCheck.check("انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش", "لازم تحدد إيه بالضبط عايز تطلب يا أستاذ كريم، لأننا بنبيع أكل شاورما ومشويات مش مياه معدنية.\n\nعايز ساندوتش شاورما فراخ ولا لحمة؟", REST, List.of("مياه-معدنية")))
                .extracting(FactCheck.Problem::kind).contains("FALSE_DENIAL");
        // an option the product does not have, agreed to or left open
        assertThat(kinds(REST, "عايز ساندوتش شاورما ميكس فراخ ولحمة عيش بلدي", "جاهز، مطلوب ساندوتش شاورما ميكس فراخ ولحمة على عيش بلدي.")).contains("OPTION_STOCK");
        assertThat(kinds(REST, "عايز مياه معدنية ١ لتر", "مياه معدنية ١ لتر متاحة عندنا بسعر ١٥ جنيه. تحب أضيفها لطلبك؟")).isNotEmpty();
        assertThat(kinds(REST, "مياه معدنية ١ لتر عندكم؟", "عندنا مياه معدنية ١ لتر بسعر ١٥ جنيه. تحب أضيفها لطلبك؟")).isNotEmpty();
        assertThat(kinds(FASH, "عايز طقم رياضي نسائي فري سايز", "عندنا تشكيلة من الطقم الرياضي النسائي، ممكن يكون فري سايز أو مقاسات مختلفة حسب القطعة. حضرتك عايزة أظبطلك طلبك دلوقتي؟")).isNotEmpty();
        // availability asked and never answered
        assertThat(kinds(REST, "عندكم تشيز برجر دبل؟", "يا فندم، معاهم حق عليا إننا مش لسه بنفتح دلوقتي (بنفتتح من ١٢)، لكن ممكن نحجزهولك تاني ويجهز مع الفتح. عدا كده، هل انت بتسأل عن تشيز برجر دبل تحديداً ولا عندك طلب تاني؟")).contains("STOCK_MISSING");
        // right answers stay
        assertThat(kinds(FASH, "عايز كارديجان تريكو نسائي XXL", "عذراً يا فندم، الكارديجان ده متاح من مقاس S لحد XL بس، من غير XXL. لو مقاسك 46، الـ XL ممكن يناسبك والقماشة فيها مطاطية شوية، وساعتها متوفر منه بيج ورمادي ووردي بـ 690 جنيه. تحب أحجزهولك بلون معين؟")).isEmpty();
        assertThat(kinds(TECH, "عندكم ساعة أطفال بلس كيدز بشريحة وردي؟", "أيوة يا فندم، متوفرة عندنا 🌸\n\nساعة أطفال بلس كيدز بشريحة باللون الوردي بسعر 1799 جنيه، وفيها مكالمات بشريحة، تتبع مكان GPS وزرار طوارئ، ومناسبة لسن 5-12 سنة (الشريحة نانو ومش داخل معاها).\n\nتحب أحجزها ليك؟")).isEmpty();
    }

    @Test
    void theSlipsOfExamRoundFiveAreCaught() {
        // a value the product does not have, neither agreed to nor answered: code says no and names the real ones, the sentences offering it go
        var fatta = FactCheck.check("فتة شاورما فراخ عادي عندكم؟", "نعم، عندنا فتة شاورما فراخ. تحب الحجم العادي ولا الكبير؟", REST, List.of());
        assertThat(fatta).extracting(FactCheck.Problem::kind).contains("OPTION_STOCK");
        assertThat(FactCheck.fix("فتة شاورما فراخ عادي عندكم؟", "نعم، عندنا فتة شاورما فراخ. تحب الحجم العادي ولا الكبير؟", REST, List.of())).contains("صغير، كبير").doesNotContain("العادي ولا");
        assertThat(kinds(REST, "عايز وينجز مقرمشة ٢٠ قطعة", "الوينجز متوفر عندنا. هل تحب أجهزلك الـ ٢٠ قطعة دلوقتي؟")).contains("OPTION_STOCK");
        assertThat(kinds(TECH, "موبايل نوفا X10 رمادي عندكم؟", "حضرتك، موبايل نوفا X10 متوفر عندنا. هل عايز تعرف سعره وتوافر اللون الرمادي بالظبط؟")).contains("OPTION_STOCK");
        assertThat(kinds(TECH, "عايز ساعة ذكية بلس برو أبيض", "حضرتك عايز ساعة ذكية بلس برو أبيض، صح؟ تحب أشوفلك الخيارات المتاحة دلوقتي؟")).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "عايز كوتشي بناتي 45", "بالنسبة للكوتشي البناتي مقاس ٤٥، ده مقاس كبير جداً على كوتشي البنات اللي عندنا. الكوتشي ده لحد؟")).contains("OPTION_STOCK");
        // the product itself denied while asking another value: code says it is there, with its price
        assertThat(FactCheck.fix("عايز ساندوتش شيش طاووق عيش بلدي", "للأسف الساندوتش ده مش من أصنافنا. تحب أرشحلك حاجة من المنو؟", REST, List.of())).contains("صاج، فينو").doesNotContain("مش من أصنافنا");
        // a colour said for another product that it does not have («X12 … الرمادي»)
        assertThat(kinds(TECH, "موبايل نوفا X10 رمادي عندكم؟", "الألوان المتاحة في نوفا X10 هي الأسود والفضي بس يا فندم. لو الرمادي مهم لك، نوفا X12 عنده اللون الفضي والرمادي في الشكل. تحب أوريك صور؟")).contains("INVENTED_COLOR");
        // right answers stay: a no, the full list, a range, another product's value, a no to a number of pieces, a no to an option
        assertThat(kinds(TECH, "موبايل نوفا X10 رمادي عندكم؟", "للأسف مفيش رمادي في نوفا X10. المتاح أسود وفضي.")).isEmpty();
        assertThat(kinds(REST, "عايز برجر كلاسيك كبير", "عندنا برجر كلاسيك بحجمين: سنجل بـ120 والدبل بـ175. أظبطهولك دبل؟")).isEmpty();
        assertThat(kinds(FASH, "عايز كوتشي بناتي 45", "المقاسات المتاحة من 26 لحد 32 بس يا فندم.")).isEmpty();
        assertThat(kinds(REST, "عايز تشيز برجر وبيبسي كانز", "تمام، تشيز برجر سنجل بـ135 ومشروب غازي بيبسي كانز. تحب أجهزهم؟")).isEmpty();
        assertThat(kinds(TECH, "عايز 32 قطعة من فلاشة زينترا USB 3.2 أسود 256 جيجا", "أسف يا فندم، من الفلاشة دي متوفر عندنا حالياً 30 قطعة بس في الأسود 256 جيجا — مفيش 32. تحب أخدلك الـ 30 دي؟")).isEmpty();
        assertThat(kinds(FASH, "عباية كريب سادة بييجي فري سايز؟", "لأ يا فندم، عباية الكريب السادة دي مفيهاش فري سايز، مقاساتها M و L و XL و XXL بس، وسعرها ١٢٠٠ جنيه ومتوفرة بأسود وكحلي وبيج. تحب أحجزلك مقاس؟")).isEmpty();
    }

    @Test
    void anUnsureReplyIsNoAnswer() {
        String reply = "مفيش تأكيد حالي على المخزون في النظام، ممكن يتفاجى حسب آخر تحديث. تحب نتأكد معاك من التوافر الحالي قبل ما نجهز الطلب؟";
        String customer = "جراب آيفون 14 سيليكون أبيض آيفون 14 برو موجود ولا خلص؟";
        assertThat(kinds(TECH, customer, reply)).contains("STOCK_MISSING");
        assertThat(FactCheck.fix(customer, reply, TECH, List.of())).contains("متاح").doesNotContain("مفيش تأكيد");
        // the price of a sold-out option, answered «no data»: code says it is sold out
        assertThat(FactCheck.fix("موبايل نوفا A5 أخضر 64 جيجا بكام؟", "النسخة دي (نوفا A5 أخضر 64 جيجا) مش لاقي لها بيانات سعر أو توفر عندي حالياً.", TECH, List.of())).contains("خلص");
        // «غير متوفرة» is the answer, whatever else is offered
        assertThat(kinds(FASH, "شنطة سفر هارد أحمر ٢٤ بوصة متاحة؟", "للأسف، شنطة السفر الهارد باللون الأحمر مقاس ٢٤ بوصة غير متوفرة حالياً. هل تحب نتحقق من توفر اللون الأحمر بمقاسات تانية؟"))
                .doesNotContain("OPTION_STOCK", "STOCK_MISSING");
    }

    @Test
    void moreThanTheStockMustBeAnsweredWithTheRealCount() {
        String customer = "عايز 9 قطعة من بلوزة بكم منفوخ أسود مقاس S";
        String reply = "عندنا بلوزة بكم منفوخ باللون الأسود، متاحة بالمقاسات S, M, L, XL. السعر 540 جنيه للقطعة.\n\nعايز أجهزلك الـ 9 قطع بالسود مقاس S؟";
        assertThat(kinds(FASH, customer, reply)).contains("STOCK_QUANTITY");
        assertThat(FactCheck.fix(customer, reply, FASH, List.of())).contains("4").doesNotContain("9 قطع");
        assertThat(kinds(FASH, "عايز 14 قطعة من عباية مطرزة أسود M", "عذراً يا فندم، الكمية المتاحة حالياً من عباية مطرزة أسود مقاس M هي 17 قطعة فقط.")).contains("STOCK_QUANTITY");
        assertThat(kinds(FASH, "عايز 7 قطعة من باليرينا جلد بيج 39", "الباليرينا بيج مقاس 39 متوفرة بس للأسف فيه قطعتين بس في المخزن، مش 7 🙏")).doesNotContain("STOCK_QUANTITY");
        // a kitchen's 300 is no limit to tell
        assertThat(kinds(REST, "عايز 310 قطعة بوب كورن تشيكن صغير حار", "تمام يا فندم، دي كمية كبيرة، هتتحضر لما المحل يفتح.")).doesNotContain("STOCK_QUANTITY");
    }

    @Test
    void anOptionTheProductLacksIsNeverListedWithItsOwn() {
        assertThat(kinds(REST, "ساندوتش شاورما فراخ عيش بلدي عندكم؟", "عندنا ساندوتش شاورما فراخ، بس العيش المتاح دلوقتي هو عيش بلدي أو فينو.")).contains("OPTION_STOCK");
        assertThat(kinds(FASH, "تيشيرت أوفر سايز مطبوع بييجي فري سايز؟", "أيوه، تيشيرت الأوفر سايز المطبوع بييجي بمقاسات كبيرة جداً (XL و XXL)، وده بيقرب من فري سايز.")).contains("OPTION_STOCK");
        // right answers: another product has it, «مجيهش», a line under another product, «لما يوصل»
        assertThat(kinds(REST, "عايز ساندوتش شاورما ميكس فراخ ولحمة عيش بلدي", "ساندوتش الشاورما ميكس فراخ ولحمة موجود، بس خبزه بيجي صاج أو عيش سوري أو فينو. ولو نفسك في عيش بلدي تحديداً، عندنا ساندوتش كفتة بيجي بعيش بلدي بـ ٧٠ جنيه."))
                .doesNotContain("OPTION_STOCK");
        assertThat(kinds(FASH, "بنطلون تشينو أحمر عندكم؟", "للأسف يا فندم، التشينو عندنا مجيهش أحمر — الألوان المتاحة: بيج، كحلي، زيتي وأسود، وبـ 690 جنيه.")).doesNotContain("OPTION_STOCK");
        assertThat(kinds(TECH, "عايز ساعة ذكية بلس برو أبيض", "عندنا ساعتين ذكيتين من بلس:\n\n1. ساعة ذكية بلس برو (7499 جنيه):\n - اللون المتاح: أسود أو فضي\n\n2. ساعة ذكية بلس 5 (3999 جنيه):\n - الألوان المتاحة: أسود، أبيض، وردي"))
                .doesNotContain("OPTION_STOCK");
    }

    @Test
    void aDenialWithNoProductNamedIsReadForTheProductOfTheChat() {
        String reply = "عذراً يا أستاذ كريم، الكابل اللي طلبته (أبيض 2 متر) نفد من المخزن حالياً. لكن عندنا نفس الموديل باللون الأبيض بطول 1 متر بسعر 199 جنيه.";
        assertThat(kinds(TECH, "انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش", reply)).doesNotContain("OPTION_STOCK");   // no product known: nothing to judge
        assertThat(FactCheck.check("انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش", reply, TECH, List.of(slugOf(TECH, "كابل فولتا USB-C لـ Lightning"))))
                .extracting(FactCheck.Problem::kind).contains("OPTION_STOCK");
        // «مفيهوش غير الأبيض» is "only white", and a value after a plain «متاح» is offered
        assertThat(kinds(TECH, "طقم شاحن 20 وات + كابل USB-C لـ Lightning بكام؟", "الطقم ده بـ 549 جنيه (بدل 598)\n- مفيهوش غير اللون الأبيض\n- الشحن متاح")).doesNotContain("OPTION_STOCK");
        assertThat(kinds(TECH, "جراب آيفون 14 سيليكون وردي آيفون 14 متاح؟", "للأسف ده مش متوفر حالياً يا فندم 😅 الوردي متاح بس مقاس آيفون 14 برو بنفس السعر 249 بدل 299")).doesNotContain("OPTION_STOCK");
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

    @Test
    void aSizeTheProductDoesNotHaveIsCaught() {
        assertThat(kinds(REST, "ضيفه", "تمام! ساندوتش شاورما فراخ دبل بفينو.")).contains("OPTION_STOCK");
        assertThat(kinds(REST, "ضيفه", "تمام! ساندوتش شاورما فراخ كبير بفينو بـ ١١٠ جنيه.")).doesNotContain("OPTION_STOCK");
    }

    @Test
    void moreThanTheStockIsCaughtForAnyProduct() {
        FactCheck.Shop TECH = fixture("tech-zone");
        int stock = TECH.product(slugOf(TECH, "موبايل نوفا ميني 8")).variants().stream().mapToInt(FactCheck.Variant::available).sum();
        String reply = "موبايل نوفا ميني 8 متوفر بالكمية اللي محتاجها: 128 جيجا 10999 جنيه.";
        if (stock < 10) assertThat(kinds(TECH, "عايز ١٠ موبايلات نوفا ميني 8 للشركة", reply)).contains("STOCK_QUANTITY");
        assertThat(kinds(TECH, "عايز ٢ موبايل نوفا ميني 8", reply)).doesNotContain("STOCK_QUANTITY");
    }

    @Test
    void productsTheShopDoesNotSellAreCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        String reply = "عندنا موبايلات من عدة ماركات عالمية:\n\n- سامسونج جالاكسي A16 – متوفر بألوان أسود، فضي، بنفسجي\n- آيفون 15 – متوفر بعدة ألوان\n- شاومي ريدمي نوت 13 برو – متوفر بألوان أخضر، أسود\n\nحضرتك عايز ميزانية معينة؟";
        assertThat(kinds(TECH, "عايز موبايل", reply)).contains("INVENTED_PRODUCTS");
        assertThat(kinds(TECH, "عايز موبايل", "عندنا:\n- موبايل نوفا A5 — 5499 جنيه\n- موبايل نوفا A7 — 7499 جنيه\n- موبايل نوفا X12 — 14999 جنيه")).doesNotContain("INVENTED_PRODUCTS");
    }

    @Test
    void aNearProductInsteadOfTheExactOneIsCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "عايز جراب لنوفا X12 برو", "عندنا جراب جلد قلاب نوفا X12 بـ ٣٤٩ جنيه، جيب كروت. تحب أنهي لون؟")).contains("BETTER_MATCH");
        assertThat(kinds(TECH, "عايز جراب لنوفا X12 برو", "عندنا جراب نوفا X12 برو ضد الصدمات بـ ٢٩٩ جنيه. تحب أنهي لون؟")).doesNotContain("BETTER_MATCH");
    }

    @Test
    void aPriceWithItsOptionInBracketsIsChecked() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "نوفا ميني 8 بكام؟", "موبايل نوفا ميني 8 بـ 10999 جنيه (256 جيجا).")).contains("OPTION_PRICE");
        assertThat(kinds(TECH, "نوفا ميني 8 بكام؟", "موبايل نوفا ميني 8 بـ 12499 جنيه (256 جيجا).")).doesNotContain("OPTION_PRICE");
        assertThat(kinds(TECH, "نوفا X12 بكام؟", "موبايل نوفا X12 بـ 12999 (256 جيجا).")).contains("OPTION_PRICE");
        assertThat(kinds(TECH, "نوفا X12 بكام؟", "موبايل نوفا X12 بـ 15499 (256 جيجا) أوبن بوكس.")).doesNotContain("OPTION_PRICE");
    }

    @Test
    void brandsTheShopDoesNotSellAreCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "عايز موبايل", "عندنا موبايل نوفا A5 بـ 5499 جنيه. لكن عندنا تاني موديلات من هونور أو شاومي لو تحب.")).contains("INVENTED_PRODUCTS");
        assertThat(kinds(TECH, "عندكم سامسونج؟", "مش بنبيع سامسونج، بس عندنا موبايل نوفا A5 بـ 5499 جنيه.")).doesNotContain("INVENTED_PRODUCTS");
        assertThat(kinds(TECH, "جراب آيفون 15 برو ماكس بكام؟", "عندنا جراب آيفون 15 شفاف ماج سيف بـ 379 جنيه.")).doesNotContain("INVENTED_PRODUCTS");
    }

    @Test
    void aSoldOutComboStillOfferedIsReplacedByTheRealStock() {
        String reply = "أيوه يا فندم، حذاء السواريه اللامع متاح باللون الذهبي ومقاس ٣٨ موجود في المخزن ✅ سعره ١١٥٠ جنيه.\nتحب أحضّرهولك؟";
        String q = "حذاء السواريه اللامع ذهبي مقاس ٣٨ موجود؟";
        if (FASH.product(slugOf(FASH, "حذاء سواريه لامع")).variants().stream().anyMatch(v -> v.options().containsValue("ذهبي") && v.options().containsValue("38") && v.available() == 0)) {
            assertThat(kinds(FASH, q, reply)).contains("OPTION_STOCK");
            assertThat(FactCheck.fix(q, reply, FASH, List.of())).contains("خلص حالياً").doesNotContain("موجود في المخزن");
        }
    }

    @Test
    void anotherProductsPriceAndALeftOutProductAreCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "عايز تلفزيون ٦٥ بوصة", "عندنا:\n1. تلفزيون فيستا HD – ٢٥٩٩٩ جنيه (شاشة عادية)\n2. تلفزيون فيستا 4K سمارت – ٢٧٤٩٩ جنيه")).contains("OPTION_PRICE");
        assertThat(kinds(TECH, "عايز تلفزيون ٦٥ بوصة", "عندنا:\n1. تلفزيون فيستا 4K سمارت – ٢٧٤٩٩ جنيه\n2. تلفزيون فيستا QLED – ٣٤٩٩٩ جنيه")).doesNotContain("OPTION_PRICE");
        assertThat(kinds(TECH, "سماعة سونيك بادز برو بتشتغل مع الآيفون؟", "لا يا فندم، عندنا حالياً موديلات تانية بس:\n1. سماعة رقبة رياضية سونيك (599 جنيه)")).contains("BETTER_MATCH");
        assertThat(kinds(TECH, "سماعة سونيك بادز برو بتشتغل مع الآيفون؟", "أيوه، سماعة سونيك بادز برو بتشتغل مع الآيفون، سعرها 2499 جنيه.")).doesNotContain("BETTER_MATCH");
    }

    @Test
    void aDenialNextToOtherSizesIsStillCaught() {
        String q = "الجاكيت البافر الشتوي الرجالي زيتي XXL موجود؟";
        String reply = "الجاكيت البافر الشتوي الرجالي زيتي موجود لكن XXL مش متاح منه حالياً (الزيتي متاح بس في S و M). المقاس XXL متاح في الأسود والكحلي بسعر 1850.";
        boolean inStock = FASH.product(slugOf(FASH, "جاكيت بافر شتوي رجالي")).variants().stream().anyMatch(v -> v.options().containsValue("زيتي") && v.options().containsValue("XXL") && v.available() > 0);
        if (inStock) assertThat(kinds(FASH, q, reply)).contains("OPTION_STOCK");
    }

    @Test
    void everyAmountNextToAProductIsOneOfItsPrices() {
        FactCheck.Shop TECH = fixture("tech-zone");
        // a second wrong amount in the same line is caught too, and the fix writes the real prices
        String reply = "موبايل نوفا X12: جديد 14,999 جنيه، أوبن بوكس 12,999 جنيه.";
        assertThat(kinds(TECH, "نوفا X12 بكام؟", reply)).contains("OPTION_PRICE");
        assertThat(FactCheck.fix("نوفا X12 بكام؟", reply, TECH, List.of())).contains("13499").doesNotContain("12,999");
        // right ones, a delivery fee and a coupon minimum in the same line are fine
        assertThat(kinds(TECH, "نوفا X12 بكام؟", "موبايل نوفا X12: جديد 14,999 جنيه، أوبن بوكس 13,499 جنيه، والشحن 100 جنيه ومجاني فوق 5000 جنيه.")).doesNotContain("OPTION_PRICE");
        assertThat(kinds(TECH, "عايز تلفزيون", "تلفزيون فيستا 4K سمارت 55 بوصة بـ 20,499 والتركيب بيزود 450 جنيه.")).doesNotContain("OPTION_PRICE");
    }

    @Test
    void anOptionWithAUnitTheProductDoesNotHaveIsCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "عايز تلفزيون كبير", "تلفزيون فيستا HD متاح 65 بوصة بـ 7999 جنيه.")).contains("OPTION_STOCK");
        assertThat(FactCheck.fix("عايز تلفزيون كبير", "تلفزيون فيستا HD متاح 65 بوصة بـ 7999 جنيه.", TECH, List.of())).contains("32 بوصة").doesNotContain("65 بوصة");
        assertThat(kinds(TECH, "نوفا X12 بكام؟", "موبايل نوفا X12 بـ 14999 جنيه، رامات 8 جيجا وشاشة 6.5 بوصة.")).doesNotContain("OPTION_STOCK");
        assertThat(kinds(TECH, "نوفا X10؟", "موبايل نوفا X10 بـ 11499: شاشة AMOLED و8 جيجا رامات.")).doesNotContain("OPTION_STOCK");
    }

    @Test
    void whatTheCustomerAskedAboutANamedProductIsAnswered() {
        FactCheck.Shop TECH = fixture("tech-zone");
        // a price asked and not given: code writes it
        String q = "عايز اعرف سعر تابلت نوفا تاب 10 رمادي 128 جيجا واي فاي + شريحة";
        String r = "حضرتك عايز تابلت نوفا تاب 10 رمادي 128 جيجا، صح؟ عشان أتأكد هنادرك على بيانات المنتج. تحب أجهزلك الطلب؟";
        assertThat(kinds(TECH, q, r)).contains("PRICE_MISSING");
        assertThat(FactCheck.fix(q, r, TECH, List.of())).contains("11499");
        assertThat(kinds(TECH, q, "تابلت نوفا تاب 10 رمادي 128 جيجا واي فاي + شريحة بـ 11,499 جنيه.")).doesNotContain("PRICE_MISSING");
        // the colours asked and only some given
        String c = "سماعة رأس سونيك ماكس متاح بألوان ايه؟";
        assertThat(kinds(TECH, c, "سماعة رأس سونيك ماكس متاحة حالياً باللون الأسود فقط، بسعر 3999 جنيه.")).contains("OPTIONS_MISSING");
        assertThat(kinds(TECH, "جراب نوفا X12 برو ضد الصدمات متاح بألوان ايه؟", "جراب نوفا X12 برو ضد الصدمات متاح بالأسود والأزرق، بـ 299 جنيه.")).doesNotContain("OPTIONS_MISSING");
        // an option this product does not have, agreed to
        String b = "عايز ساندوتش شاورما ميكس فراخ ولحمة عيش بلدي";
        String agreed = "ساندوتش الشاورما ميكس فراخ ولحمة متاح، وهنجهزه عيش بلدي زي ما حضرتك طلبت.";
        if (REST.names().stream().anyMatch(n -> n.name().equals("ساندوتش شاورما ميكس فراخ ولحمة"))) {
            assertThat(kinds(REST, b, agreed)).contains("OPTION_STOCK");
            assertThat(kinds(REST, b, "ساندوتش شاورما ميكس فراخ ولحمة مش بيتعمل عيش بلدي، المتاح صاج أو عيش سوري أو فينو.")).doesNotContain("OPTION_STOCK");
        }
        // availability asked, not answered
        assertThat(kinds(TECH, "عندكم سماعة سلك سونيك USB-C أبيض؟", "تحب أجهزهالك ولا حاجة تانية تفيدك؟")).contains("STOCK_MISSING");
    }

    /**
     * Every product and every option combination of the three shops: a right reply (its real price, in stock or sold out said truly) raises nothing, a wrong one (another price, a
     * sold-out combination offered) is caught, and no reply ever breaks the checks. This is what makes the checks hold for any catalog, not only the replies seen so far.
     */
    @Test
    void everyCombinationOfEveryProductIsJudgedRight() {
        List<String> falseAlarms = new ArrayList<>(), missed = new ArrayList<>();
        int right = 0, wrong = 0;
        for (String slug : List.of("tech-zone", "nile-style", "beit-elshawarma")) {
            FactCheck.Shop shop = fixture(slug);
            for (var n : shop.names()) {
                FactCheck.Product p = shop.product(n.slug());
                for (FactCheck.Variant v : p.variants()) {
                    String opts = String.join(" ", v.options().values());
                    String q = (n.name() + " " + opts).trim() + " بكام؟";
                    String price = String.valueOf((long) v.price());
                    String good = v.available() > 0 ? n.name() + " " + opts + " متاح بـ " + price + " جنيه. تحب أجهزهولك؟"
                                                    : n.name() + " " + opts + " خلص حالياً، بس سعره كان " + price + " جنيه.";
                    right++;
                    var found = FactCheck.check(q, good.replaceAll("\\s+", " "), shop, List.of());
                    for (var f : found) if (Set.of("OPTION_PRICE", "OPTION_STOCK", "FALSE_DENIAL", "PRICE_MISSING", "STOCK_QUANTITY", "CHECK_ERROR").contains(f.kind())) falseAlarms.add(slug + " | " + good + " -> " + f.kind() + ": " + f.note());
                    FactCheck.fix(q, good, shop, List.of());
                    // a wrong price: no price of this product
                    long bad = (long) v.price() + 1000;
                    if (p.variants().stream().noneMatch(x -> (long) x.price() == bad || (long) x.before() == bad)) {
                        wrong++;
                        String reply = n.name() + " " + opts + " متاح بـ " + bad + " جنيه.";
                        if (FactCheck.check(q, reply, shop, List.of()).stream().noneMatch(f -> f.kind().equals("OPTION_PRICE"))) missed.add(slug + " | price | " + reply);
                    }
                    // the same right reply in other words: Arabic digits with a thousands comma, English, a list line under the name
                    String ar = String.format("%,d", (long) v.price()).replace(",", "،").chars().mapToObj(ch -> Character.isDigit(ch) ? String.valueOf((char) ('٠' + ch - '0')) : String.valueOf((char) ch)).collect(java.util.stream.Collectors.joining()).replace("،", "٬");
                    for (String other : new String[]{
                            n.name() + " " + opts + " بسعر " + ar + " جنيه" + (v.available() > 0 ? " ✅" : "، بس للأسف خلص حالياً"),
                            v.available() > 0 ? "We have " + n.name() + " " + opts + " for " + String.format("%,d", (long) v.price()) + " EGP." : n.name() + " " + opts + " is sold out right now.",
                            n.name() + ":\n- " + (opts.isEmpty() ? "السعر" : opts) + ": " + price + " جنيه" + (v.available() > 0 ? "" : " (خلص)")}) {
                        right++;
                        for (var f : FactCheck.check(q, other, shop, List.of())) if (Set.of("OPTION_PRICE", "OPTION_STOCK", "FALSE_DENIAL", "PRICE_MISSING", "STOCK_QUANTITY", "CHECK_ERROR").contains(f.kind())) falseAlarms.add(slug + " | " + other + " -> " + f.kind() + ": " + f.note());
                    }
                    // an option that IS in stock said to be sold out
                    if (v.available() > 0 && !v.options().isEmpty()) {
                        wrong++;
                        String reply = "للأسف " + n.name() + " " + opts + " خلص حالياً.";
                        if (FactCheck.check(n.name() + " " + opts + " موجود؟", reply, shop, List.of()).stream().noneMatch(f -> f.kind().equals("OPTION_STOCK"))) missed.add(slug + " | denied | " + reply);
                    }
                    // a sold-out combination offered
                    if (v.available() <= 0 && !v.options().isEmpty()) {
                        wrong++;
                        String reply = n.name() + " " + opts + " متاح ✅ تحب أجهزهولك؟";
                        if (FactCheck.check(n.name() + " " + opts + " موجود؟", reply, shop, List.of()).stream().noneMatch(f -> f.kind().equals("OPTION_STOCK"))) missed.add(slug + " | soldout | " + reply);
                    }
                }
            }
        }
        // the answer lock, per product: a price asked and not given, the colours asked and only some given
        for (String slug : List.of("tech-zone", "nile-style", "beit-elshawarma")) {
            FactCheck.Shop shop = fixture(slug);
            for (var n : shop.names()) {
                FactCheck.Product p = shop.product(n.slug());
                if (p.variants().stream().noneMatch(v -> v.available() > 0)) continue;
                wrong++;
                String noPrice = "أهلاً بيك يا فندم 🌸 " + n.name() + " من أحلى الحاجات عندنا. تحب أوريك الصور؟";
                if (FactCheck.check(n.name() + " بكام؟", noPrice, shop, List.of()).stream().noneMatch(f -> f.kind().equals("PRICE_MISSING"))) missed.add(slug + " | no price | " + noPrice);
                Set<String> colours = new LinkedHashSet<>();
                for (var v : p.variants()) if (v.available() > 0) v.options().forEach((k, val) -> { if (FactCheck.colorOption(k)) colours.add(val); });
                if (colours.size() >= 2) {
                    wrong++;
                    String some = n.name() + " متاح باللون " + colours.iterator().next() + " بس.";
                    if (FactCheck.check(n.name() + " متاح بألوان ايه؟", some, shop, List.of()).stream().noneMatch(f -> f.kind().equals("OPTIONS_MISSING"))) missed.add(slug + " | colours | " + some);
                    right++;
                    String all = n.name() + " متاح بالألوان: " + String.join("، ", colours) + ".";
                    for (var f : FactCheck.check(n.name() + " متاح بألوان ايه؟", all, shop, List.of())) if (!Set.of("BETTER_MATCH", "WRONG_KIND").contains(f.kind())) falseAlarms.add(slug + " | " + all + " -> " + f.kind() + ": " + f.note());
                }
            }
        }
        System.out.println("COMBOS right=" + right + " falseAlarms=" + falseAlarms.size() + " wrong=" + wrong + " missed=" + missed.size());
        falseAlarms.stream().limit(40).forEach(x -> System.out.println("FALSE " + x));
        missed.stream().limit(40).forEach(x -> System.out.println("MISSED " + x));
        assertThat(falseAlarms).isEmpty();
        assertThat(missed).isEmpty();
    }

    @Test
    void aSoldOutOptionThatHoldsTheProductsNameIsCaught() {
        FactCheck.Shop TECH = fixture("tech-zone");
        String q = "عايز اسكرينة جلاس آيفون 15 آيفون 15 برو ماكس";
        boolean soldOut = TECH.product(slugOf(TECH, "اسكرينة جلاس آيفون 15")).variants().stream().anyMatch(v -> v.options().containsValue("آيفون 15 برو ماكس") && v.available() <= 0);
        if (soldOut) {
            assertThat(kinds(TECH, q, "تمام يا فندم، اسكرينة الجلاس لآيفون 15 برو ماكس متاحة 👌\nعشان أحجزها ليك محتاج منك: الاسم، رقم الموبايل.")).contains("OPTION_STOCK");
            // a reply that never says it: code says it
            assertThat(FactCheck.fix(q, "يبعتلك الصورة دلوقتي. لو حابب نكمل الطلب ابعتلي الاسم ورقم الموبايل.", TECH, List.of())).contains("خلص حالياً");
        }
    }

    @Test
    void sizesNameNumbersAndShortDenialsAreNotMisread() {
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "عايز زينترا بوك 14 فضي ٨ جيجا ٥١٢", "لاب توب زينترا بوك 14 فضي 8 جيجا و512 جيجا SSD متاح بـ 24999 جنيه. تحب أحجزهولك؟")).doesNotContain("STOCK_QUANTITY");
        assertThat(kinds(FASH, "انا لابسة ٤٢ عادة اخد ايه في الجينز المام فيت؟", "جينز مام فيت مقاس L متوفر بـ 850 جنيه. تحبي أحجزهولك؟")).doesNotContain("STOCK_QUANTITY");
        assertThat(kinds(FASH, "عايز تيشيرت بناتي مقاس L", "التيشيرت البناتي مقاساته بالسنة من ٤ لحد ١٢ سنة، ومفيهوش مقاس L.")).doesNotContain("OPTION_STOCK");
    }

    @Test
    void rightRepliesOfABigShopAreNotFlagged() {
        assertThat(kinds(fixture("tech-zone"), "يعني ايه أوبن بوكس؟", "أوبن بوكس يعني إن العلبة فاتحة بس سليم ١٠٠٪ ومفيش أي خدش عليه.")).doesNotContain("FALSE_DENIAL");
        assertThat(kinds(fixture("tech-zone"), "عندكم تلفزيون؟", "مفيش أي تلفزيونات عندنا حالياً.")).contains("FALSE_DENIAL");
        FactCheck.Shop TECH = fixture("tech-zone");
        assertThat(kinds(TECH, "يعني ايه أوبن بوكس؟ وفرقه في السعر قد ايه في X12؟", "في X12:\n- جديد: 14,999 جنيه\n- أوبن بوكس: 13,499 جنيه")).isEmpty();
        assertThat(kinds(TECH, "تلفزيون ٥٥ بوصة 4K بكام؟", "عندنا تلفزيون فيستا 4K سمارت مقاس 55 بوصة بسعر 20,499 جنيه (بدون تركيب) أو 20,949 جنيه (مع التركيب على الحائط).")).doesNotContain("OPTION_PRICE");
        assertThat(kinds(TECH, "تابلت نوفا تاب 10 بشريحة بكام؟", "تابلت نوفا تاب 10 بشريحة متوفر بـ 10,499 جنيه (سعة 64 جيجا) أو 11,499 جنيه (سعة 128 جيجا).")).doesNotContain("OPTION_PRICE");
        assertThat(kinds(TECH, "عايز نوفا X12 برو ٥١٢ ذهبي", "تمام يا فندم، نوفا X12 برو ٥١٢ جيجا ذهبي متوفرة ✅\n- جديد: ٢٤٩٩٩ جنيه\n- أوبن بوكس: ٢٢٩٩٩ جنيه")).doesNotContain("STOCK_QUANTITY");
        assertThat(kinds(TECH, "جراب آيفون 15 برو ماكس بكام؟", "الجراب لآيفون 15 برو ماكس بـ 379 جنيه.\n\nالموجود عندنا:\n- اللون الأسود (متاح)\n- اللون الأزرق (متاح)\n- اللون البنفسجي (متاح)")).doesNotContain("INVENTED_PRODUCTS");
        assertThat(kinds(TECH, "عايز ١٠ موبايلات نوفا ميني 8 للشركة", "موبايل نوفا ميني 8 متاح: أبيض ووردي وأسود 128 جيجا بـ 10999 جنيه.")).doesNotContain("STOCK_QUANTITY");
        assertThat(kinds(TECH, "عايز ١٠ موبايلات نوفا ميني 8 للشركة", "عندنا: موبايل نوفا A5 (5499 جنيه)، موبايل نوفا A7 (7499 جنيه)، موبايل نوفا X12 (13499 جنيه).")).contains("BETTER_MATCH");
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
            String prev = null;
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
                    if (touched.isEmpty() && prev != null) {   // as the live chat does: no tool result, so the one product of the previous reply
                        var was = FactCheck.mentions(prev, shop.names()).stream().map(FactCheck.Mention::slug).distinct().toList();
                        if (was.size() == 1) touched.addAll(was);
                    }
                    prev = r;
                    for (var p : FactCheck.check(customer, r, shop, touched))
                        System.out.println("FLAG " + parts[1] + " " + id + " " + p.kind() + " :: " + p.note() + "\n      reply: " + r.replace("\n", " ⏎ ") + (p.fixed() != null ? "\n      fixed: " + p.fixed().replace("\n", " ⏎ ") : ""));
                    reply = null;
                }
                if (line.startsWith("### ")) { id = line.substring(4).trim(); prev = null; }
                else if (line.startsWith("👤 ")) customer = line.substring(2).trim();
                else if (line.startsWith("🤖 ")) reply = new StringBuilder(line.substring(2));
                else if (reply != null) reply.append("\n").append(line);
            }
        }
    }
}

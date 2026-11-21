package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Replay of the live open-box certification run (production, 2026-11-21, conversation «L-after-50»): a laptop that is sold new and open-box is asked about, then 50 unrelated products,
 * then questions about the laptop. The scripted assistant gives the exact wrong drafts the real model gave; the final text must still be about the right product with the shop's own facts.
 * Defects I-036 (wrong warranty period), I-037 (stock of the open-box answered with another product), I-038 («still valid?» confirmed with another product's options),
 * I-039 («and the new one's price and warranty?» answered with a note about another product).
 */
class OpenBoxLongChatReplayIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    UUID tenant(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String product(Tenant t, String branch, String name, int price, int was, int stock, String description, String... colours) throws Exception {
        StringBuilder values = new StringBuilder(), variants = new StringBuilder();
        for (int i = 0; i < colours.length; i++) {
            if (i > 0) { values.append(','); variants.append(','); }
            values.append('"').append(colours[i]).append('"');
            variants.append("{\"sku\":\"S-%s\",\"priceMinor\":%d,%s\"optionValues\":{\"اللون\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":%d}]}".formatted(uniq(), price * 100, was > 0 ? "\"compareAtPriceMinor\":" + (was * 100) + "," : "", colours[i], branch, stock));
        }
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"ALL\",\"name\":\"%s\",\"description\":\"%s\",\"options\":[{\"name\":\"اللون\",\"values\":[%s]}],\"variants\":[%s]}"
                .formatted(name, description, values, variants)).andExpect(status().isCreated())), "$.slug");
    }

    String say(Tenant t, String conv, String text, Long replyTo) throws Exception {
        String json = replyTo == null ? "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text) : "{\"conversationId\":\"%s\",\"message\":\"%s\",\"replyToMessageId\":%d}".formatted(conv, text, replyTo);
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", json).andExpect(status().isOk()));
    }

    long seed(UUID conv, String role, String text) {
        return jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, :r, :x) RETURNING id").param("c", conv).param("r", role).param("x", text).query(Long.class).single();
    }

    record Shop(Tenant t, UUID id, String ext, UUID conv, long quoted, java.util.Map<String, String> slugs) {}

    /** The shop of the live run (names and facts copied from the production catalog) and a chat that asked about the open-box laptop, then went through 50 other products. */
    Shop longChat(int fillers) throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = tenant(t);
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        var slugs = new java.util.HashMap<String, String>();
        slugs.put("new", product(t, branch, "لاب توب زينترا بوك 14", 24999, 0, 8, "لاب توب زينترا بوك 14 من زينترا. الشاشة: 14 بوصة FHD IPS • المعالج: كور i5 الجيل 13 • الضمان: سنة من الوكيل", "فضي", "رمادي"));
        slugs.put("open", product(t, branch, "لاب توب زينترا بوك 14 أوبن بوكس", 20999, 24999, 3, "لاب توب زينترا بوك 14 أوبن بوكس من زينترا. الحالة: أوبن بوكس: العلبة مفتوحة، الجهاز جديد مستخدمش • الشاشة: 14 بوصة FHD IPS • الضمان: ٦ شهور (أوبن بوكس: علبة مفتوحة، الجهاز سليم ١٠٠٪)", "فضي"));
        slugs.put("watch", product(t, branch, "ساعة أطفال بلس كيدز", 1799, 0, 8, "ساعة أطفال بلس كيدز بشريحة. الضمان: سنة", "أزرق", "وردي"));
        slugs.put("card", product(t, branch, "كارت ميموري microSD زينترا", 249, 0, 30, "كارت ميموري microSD زينترا. الضمان: 10 سنين", "أسود"));
        slugs.put("timer", product(t, branch, "تايمر سينثايزر", 899, 0, 5, "تايمر سينثايزر يدعم حتى 256 ميجاهرتز. الضمان: سنة", "رمادي"));
        // the live catalog has several watches and a speaker called «بوكس»: «ساعة» and «بوكس» alone name no single product there
        product(t, branch, "ساعة ذكية أمولد", 2400, 0, 6, "ساعة ذكية أمولد. الضمان: سنة", "أسود");
        product(t, branch, "ساعة رياضية جي تي", 1500, 0, 6, "ساعة رياضية. الضمان: سنة", "رمادي");
        product(t, branch, "سبيكر ساوند بوكس", 700, 0, 6, "سبيكر ساوند بوكس. الضمان: سنة", "أسود");
        String[] others = {"هاب زينترا USB-C", "حامل موبايل للعربية", "جراب نوفا A5", "شاحن عربية فولتا", "كابل فولتا USB-C", "سماعة سونيك", "باور بانك فولتا", "ماوس زينترا"};
        for (int i = 0; i < others.length; i++) product(t, branch, others[i], 150 + i * 40, 0, 20, others[i] + ". الضمان: 6 شهور", "أسود");
        String ext = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا"));
        say(t, ext, "السلام عليكم", null);
        FakeAiProvider.SCRIPT.clear();
        UUID conv = jdbc.sql("SELECT id FROM chat.conversations WHERE tenant_id = :t AND channel = 'WEB' AND external_id = :e").param("t", tenant).param("e", ext).query(UUID.class).single();
        seed(conv, "user", "زينترا بوك 14 أوبن بوكس بكام؟");
        long quoted = seed(conv, "assistant", "ده المتاح عندنا بالظبط: • لاب توب زينترا بوك 14 أوبن بوكس  - فضي — 20999 جنيه تحب أنهي مقاس أو لون؟");
        String[] filler = {"هاب زينترا USB-C", "حامل موبايل للعربية", "جراب نوفا A5", "شاحن عربية فولتا", "كابل فولتا USB-C", "سماعة سونيك", "باور بانك فولتا", "ماوس زينترا", "تايمر سينثايزر", "كارت ميموري microSD زينترا"};
        for (int i = 0; i < fillers; i++) {
            String n = filler[i % filler.length];
            seed(conv, "user", "بكام " + n + "؟");
            seed(conv, "assistant", n + " متاح يا فندم بـ " + (149 + (i % filler.length) * 40) + " جنيه. تحب أحجزهولك؟");
        }
        seed(conv, "user", "ساعة أطفال بلس متاح؟");
        seed(conv, "assistant", "متاح يا فندم ⌚ ساعة أطفال بلس كيدز بـ 1799 جنيه، أزرق ووردي. تحب أي واحد؟");
        return new Shop(t, tenant, ext, conv, quoted, slugs);
    }

    /**
     * What happened live: the primary model stalled for about 28 s, the fallback model answered from the prompt with no tool call, and the reply budget was spent, so there was no review and no
     * rewrite: the draft met only the deterministic gates. The scripted assistant sleeps 21 s (past the point where the review and the rewrites are skipped) and then writes the live wrong draft.
     */
    String asked(Shop s, String text, Long replyTo, String ignoredToolName, String badDraft) throws Exception {
        FakeAiProvider.SCRIPT.add(turns -> { try { Thread.sleep(21_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return Turn.model(badDraft); });
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model(badDraft));
        String r = JsonPath.read(say(s.t(), s.ext(), text, replyTo), "$.reply");
        FakeAiProvider.SCRIPT.clear();
        return r.replace(",", "").replace("٬", "");
    }

    @Test
    void i038_aQuoteOfTheOpenBoxPriceIsNeverConfirmedWithAnotherProductsOptions() throws Exception {
        Shop s = longChat(50);
        String reply = asked(s, "هو السعر ده لسه ساري؟", s.quoted(), "watch", "نعم يا فندم السعر ده لسه ساري 👍 تحب أجهزلك ساعة أزرق ولا وردي؟");
        assertThat(reply).as(reply).contains("20999");
    }

    @Test
    void i037_theStockOfTheOpenBoxIsNeverAnsweredWithAnotherProduct() throws Exception {
        Shop s = longChat(50);
        String reply = asked(s, "فاضل كام من الأوبن بوكس؟", null, "timer", "متاح يا فندم 📈 تايمر سينثايزر متاح بـ 899 جنيه (عربي 1099): • يدعم حتى 256 ميجاهرتز. • الضمان سنة كاملة من الوكيل. تحب أجهزهولك الآن؟");
        assertThat(reply).as(reply).doesNotContain("تايمر").doesNotContain("899").contains("20999").contains("3");
    }

    @Test
    void i039_theNewVersionsPriceAndWarrantyAreAnsweredForTheLaptopAfterAQuestionAboutItsOpenBox() throws Exception {
        Shop s = longChat(50);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح يا فندم، 3 قطع بسعر 20999، الضمان ستة شهور."));
        say(s.t(), s.ext(), "الأوبن بوكس لسه متاح؟ وضمانه؟", null);
        FakeAiProvider.SCRIPT.clear();
        String reply = asked(s, "والجديد بكام وضمانه؟", null, "card", "الجديد بـ 24,999 جنيه، وضمانه سنة من الوكيل. تحب أحجز لك واحد باللون الفضي ده؟");
        assertThat(reply).as(reply).contains("24999").doesNotContain("كارت ميموري").doesNotContain("ساعة");
    }

    @Test
    void i036_aWrongWarrantyPeriodForTheOpenBoxIsNeverSent() throws Exception {
        Shop s = longChat(0);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح يا فندم 3 قطع بسعر 20999."));
        say(s.t(), s.ext(), "الأوبن بوكس متاح؟", null);
        FakeAiProvider.SCRIPT.clear();
        String reply = asked(s, "الضمان قد إيه؟", null, "open", "الضمان شهر يا فندم لأن الجهاز أوبن بوكس، وبيشمل عيوب الصناعة بس (مش الكسر أو المية) 📝 السعر ده تخفيض خرافي، عايز أحجزلك واحد قبل ما يخلص الـ ٣ المتبقية؟");
        assertThat(reply).as(reply).doesNotContain("الضمان شهر يا فندم").contains("٦ شهور");
    }

    /** Live open-box run (ob_f20): «فيه كارت شاشة؟» about the open-box laptop, whose data says nothing about graphics, was answered «الشاشة مدمجة في الجهاز» (a screen, and «integrated» is the NEW laptop's graphics value). */
    @Test
    void theGraphicsCardQuestionOfTheOpenBoxIsNeverAnsweredWithTheNewVersionsValueOrAScreen() throws Exception {
        Shop s = longChat(0);
        String reply = asked(s, "فيه كارت شاشة؟", null, "", "الشاشة مدمجة في الجهاز، مش منفصلة — شاشة 14 بوصة FHD IPS بيضا تفتح وتقفل مع الليب توب 📺 لو عايز توصيل أو تفاصيل تانية قولي وأنا معاك!");
        assertThat(reply).as(reply).doesNotContain("مدمجة");
    }

    /** Live open-box run (ob_f20): the NEW laptop's warranty was answered with an invented «accessories 6 months» (the open-box laptop's period leaking into the new one). */
    @Test
    void anInventedAccessoriesWarrantyOfTheNewLaptopIsNeverSent() throws Exception {
        Shop s = longChat(0);
        seed(s.conv(), "user", "عايز لاب توب زينترا بوك 14 جديد");
        seed(s.conv(), "assistant", "تمام يا فندم، لاب توب زينترا بوك 14 متاح جديد بـ 24999 جنيه، فضي أو رمادي.");
        String reply = asked(s, "والضمان بتاعه؟", null, "", "الضمان سنة من الوكيل يا فندم على عيوب الصناعة، والإكسسوارات اللي جواه (الشاحن مثلاً) ليها 6 شهور. لو حصل حاجة، تبعنا الجهاز أو تجي بيه فرع المهندسين ونتابعك. تحب نحجزلك واحدة؟");
        assertThat(reply).as(reply).doesNotContain("6 شهور").doesNotContain("٦ شهور");
    }
}

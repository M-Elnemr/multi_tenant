package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Owner report (live WhatsApp, own number, shop nile-style): the customer replied to the picture of a product («كوتشي كانفاس 690 جنيه») with «دا مقاساته والوانه ايه» and the assistant answered about ANOTHER
 * product; and replied to the assistant's list of four trousers with «اخر بنطلون هنا مقاساته ايه» and got the cargo trousers (the last product the assistant had talked about) instead of the LAST ONE IN THE QUOTED LIST.
 */
class WhatsAppQuotedProductIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeEvolutionApi.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String product(Tenant t, String branch, String name, int price, String... sizes) throws Exception {
        StringBuilder variants = new StringBuilder(), values = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) { variants.append(','); values.append(','); }
            values.append('"').append(sizes[i]).append('"');
            variants.append("{\"sku\":\"S-%s\",\"priceMinor\":%d,\"optionValues\":{\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}".formatted(uniq(), price * 100, sizes[i], branch));
        }
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"ALL\",\"name\":\"%s\",\"options\":[{\"name\":\"المقاس\",\"values\":[%s]}],\"variants\":[%s]}".formatted(name, values, variants)).andExpect(status().isCreated())), "$.slug");
    }

    static final String LIST = "أهلاً أستاذ محمود 🌸 عندنا رجالي من البناطيل:\n\n- بنطلون جوجر قطن — 480 جنيه\n- بنطلون تشينو — 690 جنيه\n- بنطلون كارجو — 780 جنيه\n- بنطلون قماش رسمي — 820 جنيه\n\nكلهم من S لحد XXL. عايز كاجوال ولا رسمي؟";

    record Chat(java.util.function.BiFunction<String, String, ResultActions> hook, UUID tenant, java.util.List<String> slugs) {}

    Chat chat(String suffix) throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        var slugs = new java.util.ArrayList<String>();
        slugs.add(product(t, branch, "بنطلون جوجر قطن " + suffix, 480, "S", "M", "L"));
        slugs.add(product(t, branch, "بنطلون تشينو " + suffix, 690, "S", "M", "L"));
        slugs.add(product(t, branch, "بنطلون كارجو " + suffix, 780, "S", "M", "L", "XL", "XXL"));
        slugs.add(product(t, branch, "بنطلون قماش رسمي " + suffix, 820, "S", "M", "L"));
        slugs.add(product(t, branch, "كوتشي كانفاس " + suffix, 690, "40", "41", "42"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(status().isOk());
        String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings es JOIN core.tenants c ON c.id = es.tenant_id WHERE c.slug = :s").param("s", t.slug()).query(String.class).single();
        String secret = "evolution-test-secret-0123456789";
        String num = "201055" + (100000 + System.nanoTime() % 800000);
        return new Chat((id, inner) -> {
            try { return mvc.perform(post("/api/v1/chat/evolution/webhook/" + secret).header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"key\":{\"remoteJid\":\"%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"%s\"},\"message\":%s}}".formatted(inst, num, id, inner))); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, tenant, slugs);
    }

    String lastReply(UUID tenant) {
        return jdbc.sql("SELECT m.content FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND m.role = 'assistant' ORDER BY m.id DESC LIMIT 1").param("t", tenant).query(String.class).single();
    }

    @Test
    void aReplyToTheProductPicturesCaptionIsAboutThatProductNotTheLastOneTalkedAbout() throws Exception {
        String sfx = uniq();
        Chat c = chat(sfx);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(LIST.replace("بنطلون", "بنطلون")));
        c.hook().apply("E-1" + uniq(), "{\"conversation\":\"عايز بنطلون\"}").andExpect(status().isOk());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("بنطلون كارجو " + sfx + " بـ 780 جنيه يا فندم، مقاساته S / M / L / XL / XXL."));
        c.hook().apply("E-2" + uniq(), "{\"conversation\":\"وريني الكارجو\"}").andExpect(status().isOk());
        // she replies to the PICTURE of the sneakers (the caption is the product card the shop sent; the picture message itself is not one we stored)
        String caption = "كوتشي كانفاس " + sfx + "\\n690 جنيه\\nhttps://nile-style.elmanassa.shop/products/x";
        for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("بنطلون كارجو " + sfx + " بـ 780 جنيه يا فندم، متوفر بـ ٣ ألوان ومقاساته: S / M / L / XL / XXL. تحب أشوفلك إيه المتاح؟"));
        c.hook().apply("E-3" + uniq(), "{\"extendedTextMessage\":{\"text\":\"دا مقاساته والوانه ايه\",\"contextInfo\":{\"stanzaId\":\"CARD-" + sfx + "\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"imageMessage\":{\"caption\":\"" + caption + "\"}}}}}").andExpect(status().isOk());
        String reply = lastReply(c.tenant());
        assertThat(reply).as(reply).contains("كوتشي").doesNotContain("كارجو").doesNotContain("780 جنيه");
    }

    @Test
    void theLastTrousersOfAQuotedListAreTheLastOnTheListNotTheLastTalkedAbout() throws Exception {
        String sfx = uniq();
        Chat c = chat(sfx);
        String list = LIST.replace("جوجر قطن", "جوجر قطن " + sfx).replace("تشينو", "تشينو " + sfx).replace("كارجو", "كارجو " + sfx).replace("قماش رسمي", "قماش رسمي " + sfx);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(list));
        c.hook().apply("E-1" + uniq(), "{\"conversation\":\"عايز بنطلون\"}").andExpect(status().isOk());
        String outId = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text LIKE '%قماش رسمي%' LIMIT 1").param("t", c.tenant()).query(String.class).optional().orElse("LIST-" + sfx);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("بنطلون كارجو " + sfx + " بـ 780 جنيه يا فندم، مقاساته S / M / L / XL / XXL."));
        c.hook().apply("E-2" + uniq(), "{\"conversation\":\"وريني الكارجو\"}").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("آخر واحد عملته كان بنطلون كارجو " + sfx + " بـ 780 جنيه يا فندم، مقاساته من S لحد XXL. وزنك تقريباً كام؟"));
        c.hook().apply("E-3" + uniq(), "{\"extendedTextMessage\":{\"text\":\"اخر بنطلون هنا مقاساته ايه\",\"contextInfo\":{\"stanzaId\":\"" + outId + "\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"conversation\":\"" + list.replace("\n", "\\n") + "\"}}}}").andExpect(status().isOk());
        String reply = lastReply(c.tenant());
        assertThat(reply).as(reply).contains("قماش رسمي").doesNotContain("780 جنيه");
    }

    /** The cards the assistant sent are recorded with their exact caption: a reply to the SNEAKERS card, sent in the same message as the cargo trousers card, is about the sneakers. */
    @Test
    void aReplyToOneOfSeveralCardsSentTogetherIsAboutThatCard() throws Exception {
        String sfx = uniq();
        Chat c = chat(sfx);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("show_products", java.util.Map.of("slugs", java.util.List.of(c.slugs().get(2), c.slugs().get(4)))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده المتاح يا فندم، تحب تشوف مقاسات أنهي واحد؟"));
        c.hook().apply("E-1" + uniq(), "{\"conversation\":\"وريني بنطلون كارجو وكوتشي كانفاس\"}").andExpect(status().isOk());
        String cardMid = jdbc.sql("SELECT provider_mid FROM chat.message_mids WHERE tenant_id = :t AND part_text LIKE '%كوتشي%' LIMIT 1").param("t", c.tenant()).query(String.class).optional().orElse(null);
        assertThat(cardMid).as("the sneakers card was recorded with its id").isNotNull();
        for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("بنطلون كارجو " + sfx + " بـ 780 جنيه يا فندم، متوفر بـ ٣ ألوان ومقاساته: S / M / L / XL / XXL. تحب أشوفلك إيه المتاح؟"));
        c.hook().apply("E-2" + uniq(), "{\"extendedTextMessage\":{\"text\":\"دا مقاساته والوانه ايه\",\"contextInfo\":{\"stanzaId\":\"" + cardMid + "\",\"participant\":\"15550001@s.whatsapp.net\",\"quotedMessage\":{\"imageMessage\":{\"caption\":\"x\"}}}}}").andExpect(status().isOk());
        String reply = lastReply(c.tenant());
        assertThat(reply).as(reply).contains("كوتشي").doesNotContain("كارجو").doesNotContain("780 جنيه");
    }
}

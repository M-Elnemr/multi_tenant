package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The seller knows who a product is for, calls the customer by name with the right respect, and answers a burst as one reply or two separate messages. The model is scripted. */
class AiHumanSellerIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tenantId(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    void jeans(Tenant t, String name, String audience) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"pants-jeans\",\"audience\":\"%s\",\"name\":\"%s\",\"variants\":[{\"sku\":\"J-%s\",\"priceMinor\":30000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}]}"
                        .formatted(audience, name, uniq(), branch)).andExpect(status().isCreated());
    }

    @Test
    void searchResultsSayWhoEachProductIsForAndTheAudienceNarrowsTheSearch() throws Exception {
        Tenant t = onboard("STORE");
        jeans(t, "Jeans Kids Blue", "BOYS");
        jeans(t, "Jeans Classic Blue", "MEN");
        UUID id = tenantId(t);

        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "jeans")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "jeans", "audience", "MEN")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("البنطلون ليك ولا لحد تاني؟"));
        chat.reply(id, "WEB", "who-" + uniq(), null, "عايز بنطلون جينز");

        String all = FakeAiProvider.RESULTS.get(0).result().toString(), men = FakeAiProvider.RESULTS.get(1).result().toString();
        assertThat(all).contains("Jeans Kids Blue").contains("boys (kids)").contains("Jeans Classic Blue").contains("for=men");
        assertThat(men).contains("Jeans Classic Blue").doesNotContain("Jeans Kids Blue");
        // the catalog digest tells the model the shop sells this item for several groups, and the rule says to know who it is for first
        assertThat(FakeAiProvider.lastSystem).contains("WHO IS IT FOR").containsPattern("Pants & Jeans[^\\n]*for [^\\n]*(men|boys)");
    }

    @Test
    void theCustomerIsCalledByNameWithTheRightRespectOrNeutrallyWhenNothingTells() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً يا أستاذة منى"));
        chat.reply(id, "WHATSAPP", "2010000" + (100000 + new java.util.Random().nextInt(899999)), null, "عندكم قمصان؟", "🌸 Mona Ali 🌸");
        assertThat(FakeAiProvider.lastSystem).contains("Their name: Mona").contains("WOMAN").contains("«أستاذة Mona»");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بحضرتك"));
        chat.reply(id, "WEB", "anon-" + uniq(), null, "عندكم شنط؟", "🔥 K.S 🔥");
        assertThat(FakeAiProvider.lastSystem).contains("Name and gender not known").doesNotContain("Their name:");

        // a name we cannot place: the model may use it only when it is clearly a man's or a woman's name, otherwise «حضرتك»
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً يا أستاذ محمد"));
        String ext = "named-" + uniq();
        chat.reply(id, "WEB", ext, null, "عندكم جزم؟", "محمد سمير");
        assertThat(FakeAiProvider.lastSystem).contains("Their name: محمد").contains("«أستاذ محمد»");
        // the name is kept on the conversation for the next messages
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام"));
        chat.reply(id, "WEB", ext, null, "مقاس ٤٢");
        assertThat(FakeAiProvider.lastSystem).contains("Their name: محمد");
    }

    @Test
    void twoSeparateTopicsBecomeTwoMessagesOnlyOnMessagingChannels() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = tenantId(t);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("طلبك في الطريق 🚚\n⸻\nوعندنا فساتين سواريه حلوة، تحبي أوريكي؟"));
        String web = chat.reply(id, "WEB", "split-" + uniq(), null, "طلبي فين؟\nوعندكم فساتين سواريه؟").text();
        assertThat(web).doesNotContain("⸻").contains("في الطريق").contains("سواريه");
        assertThat(ChatService.messages("طلبك في الطريق\n⸻\nوعندنا فساتين")).containsExactly("طلبك في الطريق", "وعندنا فساتين");
        assertThat(ChatService.messages("one\n⸻\ntwo\n⸻\nthree")).hasSize(2);
        assertThat(ChatService.messages("just one")).containsExactly("just one");
    }
}

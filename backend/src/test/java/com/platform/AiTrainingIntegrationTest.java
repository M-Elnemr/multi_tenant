package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The seller's knowledge rules (it answers product questions itself, the owner is asked only for the shop's own decisions) and the platform admin's training room. */
class AiTrainingIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String chat(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    /** A shop with one 100.00 mug in stock. */
    void mug(Tenant t) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"Mug %s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(uniq(), uniq(), branch)).andExpect(status().isCreated());
    }

    String adminToken() throws Exception {
        Tenant b = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", b.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(b.phone())).andExpect(status().isOk())), "$.accessToken");
    }

    @Test
    void aQuestionAboutTheProductItselfIsNeverHandedToTheOwnerButAWarrantyIs() throws Exception {
        Tenant t = onboard("STORE");
        mug(t);
        String conv = UUID.randomUUID().toString();
        // the incident: "something guaranteed against washing" asked to the owner before looking at the product
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "ضمان الخامة والغسيل للطقم؟")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي، تحبي أبيض ولا بيج؟"));
        chat(t, conv, "عاوز حاجه بضمان من الغسيل");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error").toString()).contains("look the product up first");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.unanswered WHERE tenant_id = :t").param("t", tenantId(t)).query(Long.class).single()).isZero();

        // a plain care question, even after a lookup, is the assistant's to answer
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "cookware")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "Can the cotton fabric go in the washing machine?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Yes, on a gentle cycle."));
        chat(t, conv, "can I wash it?");
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error").toString()).contains("Do not ask the owner");

        // a warranty promise is the shop's decision: it reaches the owner, and the assistant is then told there is an open question
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "cookware")));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "Does the mug come with a one year warranty?")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("I asked the team, I will tell you here."));
        chat(t, conv, "is there a warranty?");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.unanswered WHERE tenant_id = :t AND status = 'OPEN' AND question LIKE '%warranty%'").param("t", tenantId(t)).query(Long.class).single()).isEqualTo(1);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat(t, conv, "and the material?");
        assertThat(FakeAiProvider.lastSystem).contains("OPEN QUESTIONS").contains("one year warranty").contains("Do NOT say again");
    }

    @Test
    void anInventedUrgencyIsSentBackAndThenCut() throws Exception {
        Tenant t = onboard("STORE");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي. السعر هيغلي قريباً جداً. تحبي أبيض ولا بيج؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي في الغسالة، تحبي أبيض ولا بيج؟"));
        String reply = chat(t, UUID.randomUUID().toString(), "ينفع يتغسل؟");
        assertThat(JsonPath.<String>read(reply, "$.reply")).contains("أبيض ولا بيج").doesNotContain("هيغلي");

        // the model insists: the sentence is cut, the rest is sent
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي. السعر هيغلي قريباً جداً. تحبي أبيض ولا بيج؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي. السعر هيغلي قريباً جداً. تحبي أبيض ولا بيج؟"));
        String again = chat(t, UUID.randomUUID().toString(), "ينفع يتغسل؟");
        assertThat(JsonPath.<String>read(again, "$.reply")).contains("القطن بيتغسل").contains("أبيض ولا بيج").doesNotContain("هيغلي");
    }

    @Test
    void theTrainingRoomIsForThePlatformAdminOnly() throws Exception {
        Tenant shop = onboard("STORE");
        onHost(shop.host(), shop.access(), "GET", "/api/v1/platform/chat/train/shops", null).andExpect(status().isForbidden());
        onHost("platform.test", shop.access(), "POST", "/api/v1/platform/chat/train/" + tenantId(shop), "{\"message\":\"hi\"}").andExpect(status().isForbidden());
        onHost("platform.test", shop.access(), "POST", "/api/v1/platform/chat/train/draft-lesson", "{\"remark\":\"x\"}").andExpect(status().isForbidden());
    }

    UUID tenantId(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    @Test
    void theAdminChatsAsACustomerOfAShopPicksTheModelAndTurnsAReplyIntoALesson() throws Exception {
        Tenant shop = onboard("STORE");
        UUID id = tenantId(shop);
        String admin = adminToken();
        String base = "/api/v1/platform/chat/train/" + id;
        assertThat(body(onHost("platform.test", admin, "GET", "/api/v1/platform/chat/train/shops", null).andExpect(status().isOk()))).contains(shop.slug());

        // the chosen model answers (or shows its own error, never a silent fallback); the owner's own test chat is separate
        onHost("platform.test", admin, "POST", base, "{\"message\":\"عاوزة ملاية\",\"model\":\"glm\"}").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("GLM")));
        onHost("platform.test", admin, "GET", base, null).andExpect(status().isOk()).andExpect(jsonPath("$.model").value("glm")).andExpect(jsonPath("$.messages.length()").value(2));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلاً بحضرتك 🌸 تحبي أوريكي إيه؟"));
        onHost("platform.test", admin, "POST", base, "{\"message\":\"عاوزة ملاية\",\"model\":\"default\"}").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("أهلاً")));
        onHost("platform.test", admin, "GET", base, null).andExpect(jsonPath("$.model").value("default"));
        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/ai/test", null).andExpect(jsonPath("$.length()").value(0));
        onHost("platform.test", admin, "POST", base, "{\"message\":\"hi\",\"model\":\"nonsense\"}").andExpect(status().isBadRequest());

        // the remark becomes a draft lesson (nothing is saved yet)
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("```json\n{\"instruction\":\"Say washed, never wiped, when talking about laundering fabric.\",\"exampleQuestion\":\"اتمسحت ازاي؟\",\"exampleAnswer\":\"قصدي اتغسلت يا فندم، آسف على اللخبطة 🙏\"}\n```"));
        String draft = body(onHost("platform.test", admin, "POST", "/api/v1/platform/chat/train/draft-lesson",
                "{\"customerMessage\":\"بتتغسل؟\",\"agentReply\":\"حتى لو اتمسحت كل يوم\",\"remark\":\"المفروض يقول اتغسلت\"}").andExpect(status().isOk()));
        assertThat((String) JsonPath.read(draft, "$.instruction")).contains("washed");
        assertThat((String) JsonPath.read(draft, "$.exampleAnswer")).contains("اتغسلت");
        onHost("platform.test", admin, "POST", "/api/v1/platform/chat/train/draft-lesson", "{\"remark\":\"\"}").andExpect(status().isBadRequest());
        onHost("platform.test", admin, "GET", base + "/lessons", null).andExpect(jsonPath("$.length()").value(0));

        // saved for this shop only: the next chat of this shop reads it, another shop does not
        onHost("platform.test", admin, "POST", base + "/lessons", "{\"instruction\":\"SHOP-ONLY-LESSON-OMEGA\",\"exampleQuestion\":\"q\",\"exampleAnswer\":\"a\"}").andExpect(status().isCreated());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        onHost("platform.test", admin, "POST", base, "{\"message\":\"hi I need a dress\",\"model\":\"default\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("SHOP-ONLY-LESSON-OMEGA");
        Tenant other = onboard("STORE");
        onHost(other.host(), other.access(), "POST", "/api/v1/store/ai/test", "{\"message\":\"hi I need a dress\"}").andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).doesNotContain("SHOP-ONLY-LESSON-OMEGA");

        // a reset starts a new conversation
        onHost("platform.test", admin, "DELETE", base, null).andExpect(status().isNoContent());
        onHost("platform.test", admin, "GET", base, null).andExpect(jsonPath("$.messages.length()").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isZero();
    }
}

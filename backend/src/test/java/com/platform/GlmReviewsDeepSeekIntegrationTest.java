package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.chat.ChatService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DeepSeek answers (real OpenAiProvider, through a local stand-in for Hive) and GLM reviews (AI_REVIEW_MODEL=glm, a second real OpenAiProvider on the same stand-in): a valid draft goes out
 * untouched, a correction that keeps the facts is used, one that changes a price or brings a number nobody showed is dropped, a failing, garbled, tool-calling or hung reviewer never holds the
 * reply, and the tool calls of the answering model (searches, the order summary) are never touched by the review.
 */
class GlmReviewsDeepSeekIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Deque<Function<JsonNode, byte[]>> DEEPSEEK = new ArrayDeque<>(), GLM = new ArrayDeque<>();
    static final List<JsonNode> DEEPSEEK_SEEN = new CopyOnWriteArrayList<>(), GLM_SEEN = new CopyOnWriteArrayList<>();
    static final HttpServer HIVE;
    static volatile int glmStatus = 200;

    static {
        try {
            HIVE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HIVE.createContext("/v1/chat/completions", ex -> {
                JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
                boolean glm = req.path("model").asText().contains("glm");
                (glm ? GLM_SEEN : DEEPSEEK_SEEN).add(req);
                Function<JsonNode, byte[]> f;
                synchronized (GlmReviewsDeepSeekIntegrationTest.class) { f = (glm ? GLM : DEEPSEEK).poll(); }
                byte[] out = f == null ? text("ok") : f.apply(req);
                int code = glm && glmStatus != 200 ? glmStatus : 200;
                try { ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); } catch (java.io.IOException ignored) { /* the client moved on */ }
                ex.close();
            });
            HIVE.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            HIVE.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + HIVE.getAddress().getPort() + "/v1";
        r.add("app.ai.glm.base-url", () -> base);
        r.add("app.ai.deepseek.base-url", () -> base);
        r.add("app.ai.glm.api-key", () -> "test-hive-key");
        r.add("app.ai.deepseek.api-key", () -> "test-hive-key");
        r.add("app.ai.review.enabled", () -> "true");
        r.add("app.ai.review.model", () -> "glm");
        r.add("app.ai.review.timeout-ms", () -> "1500");
        r.add("app.ai.glm.max-rps", () -> "0");
        r.add("app.ai.deepseek.max-rps", () -> "0");
    }

    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        synchronized (GlmReviewsDeepSeekIntegrationTest.class) { DEEPSEEK.clear(); GLM.clear(); }
        DEEPSEEK_SEEN.clear(); GLM_SEEN.clear(); glmStatus = 200;
        FakeAiProvider.reset();
    }

    static byte[] json(Map<String, Object> message) {
        try { return JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message)), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] text(String t) { return json(Map.of("role", "assistant", "content", t)); }

    static byte[] toolCall(String name, Map<String, Object> args) {
        try { return json(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_1", "type", "function", "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args)))))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static void deepseek(Function<JsonNode, byte[]> f) { synchronized (GlmReviewsDeepSeekIntegrationTest.class) { DEEPSEEK.add(f); } }

    static void glm(Function<JsonNode, byte[]> f) { synchronized (GlmReviewsDeepSeekIntegrationTest.class) { GLM.add(f); } }

    record Shop(UUID id, String name, String slug) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "قميص قطن " + uniq();
        String made = onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"name\":\"%s\",\"variants\":[{\"sku\":\"S-%s\",\"priceMinor\":15000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), branch))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(id, name, JsonPath.read(made, "$.slug"));
    }

    static final String DRAFT = "القميص القطن بـ 150 جنيه ومتاح، تحب أحجزهولك؟";

    /** DeepSeek searches, gets the shop's real result, then writes the draft. */
    void deepseekAnswersWithAPrice(Shop s) {
        deepseek(req -> toolCall("search_products", Map.of("query", s.name())));
        deepseek(req -> text(DRAFT));
    }

    /** A shirt in white M (5), white L (3) and black M (none in stock). */
    Shop shirtWithColours() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String name = "قميص قطن " + uniq();
        String[][] v = {{"أبيض", "M", "5"}, {"أبيض", "L", "3"}, {"أسود", "M", "0"}};
        StringBuilder variants = new StringBuilder();
        for (String[] x : v) variants.append(variants.length() == 0 ? "" : ",").append("{\"sku\":\"SH-%s\",\"priceMinor\":15000,\"optionValues\":{\"اللون\":\"%s\",\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":%s}]}".formatted(uniq(), x[0], x[1], branch, x[2]));
        String made = onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"name\":\"%s\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أبيض\",\"أسود\"]},{\"name\":\"المقاس\",\"values\":[\"M\",\"L\"]}],\"variants\":[%s]}".formatted(name, variants))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(id, name, JsonPath.read(made, "$.slug"));
    }

    String ask(Shop s, String ext, String message) { return chat.reply(s.id(), "WEB", ext, null, message).text(); }

    @Test
    void aValidDraftIsKeptAndTheReviewerOnlySawTextAndRealFacts() throws Exception {
        Shop s = shop();
        deepseekAnswersWithAPrice(s);
        glm(req -> text("{\"ok\":true}"));
        assertThat(ask(s, "g1-" + uniq(), "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
        assertThat(DEEPSEEK_SEEN).as("search, then the answer").hasSize(2);
        assertThat(GLM_SEEN).as("one review").hasSize(1);
        JsonNode review = GLM_SEEN.get(0);
        assertThat(review.has("tools")).as("the reviewer cannot call tools").isFalse();
        String asked = review.path("messages").get(1).path("content").asText();
        assertThat(asked).contains(DRAFT).contains("150").contains("FACTS");
        assertThat(DEEPSEEK_SEEN.get(1).path("messages").toString()).as("DeepSeek got the real tool result to write from").contains("150");
        // the review is billed on the reviewer's model, apart from the reply
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id = :t AND feature = 'CHAT_REVIEW' AND model = 'glm'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aCorrectionThatKeepsTheFactsIsUsed() throws Exception {
        Shop s = shop();
        deepseekAnswersWithAPrice(s);
        glm(req -> text("{\"ok\":false,\"problems\":[\"answer first\"],\"reply\":\"سعر القميص القطن 150 جنيه يا فندم وهو متاح، تحب أي مقاس؟\"}"));
        String reply = ask(s, "g2-" + uniq(), "بكام " + s.name() + "؟");
        assertThat(reply).isEqualTo("سعر القميص القطن 150 جنيه يا فندم وهو متاح، تحب أي مقاس؟").contains("150");
    }

    @Test
    void aCorrectionThatChangesThePriceOrInventsANumberIsDroppedAndTheDraftGoesOut() throws Exception {
        Shop s = shop();
        for (String bad : new String[] {"سعر القميص القطن 120 جنيه يا فندم، تحب أي مقاس؟", "القميص القطن بـ 999 جنيه ومتاح، تحب أحجزهولك؟"}) {
            deepseekAnswersWithAPrice(s);
            glm(req -> text("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"" + bad + "\"}"));
            String reply = ask(s, "g3-" + uniq(), "بكام " + s.name() + "؟");
            assertThat(reply).as(bad).isEqualTo(DRAFT).contains("150").doesNotContain("120").doesNotContain("999");
        }
    }

    @Test
    void aReviewerThatFailsIsGarbledOrTriesToCallToolsNeverBlocksTheReply() throws Exception {
        Shop s = shop();
        // HTTP 500
        deepseekAnswersWithAPrice(s);
        glmStatus = 500;
        glm(req -> text("server error"));
        assertThat(ask(s, "g4a-" + uniq(), "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
        assertThat(GLM_SEEN).as("one try, no retry for a review").hasSize(1);
        glmStatus = 200;
        // not JSON
        GLM_SEEN.clear();
        deepseekAnswersWithAPrice(s);
        glm(req -> text("مش فاهم"));
        assertThat(ask(s, "g4b-" + uniq(), "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
        // the reviewer answers with a tool call and no text
        deepseekAnswersWithAPrice(s);
        glm(req -> toolCall("search_products", Map.of("query", "x")));
        assertThat(ask(s, "g4c-" + uniq(), "بكام " + s.name() + "؟")).isEqualTo(DRAFT);
    }

    @Test
    void aHungReviewerCostsTheCustomerAtMostTheReviewTimeoutAndIsNotRetried() throws Exception {
        Shop s = shop();
        deepseekAnswersWithAPrice(s);
        glm(req -> { try { Thread.sleep(6_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return text("{\"ok\":true}"); });
        long t = System.nanoTime();
        String reply = ask(s, "g5-" + uniq(), "بكام " + s.name() + "؟");
        double took = (System.nanoTime() - t) / 1e9;
        assertThat(reply).isEqualTo(DRAFT);
        assertThat(took).as("reply sent after the 1.5 s review cap, not after the hung review (6 s)").isLessThan(5.0);
        assertThat(GLM_SEEN).as("not retried and not sent twice").hasSize(1);
    }

    @Test
    void theOrderSummaryAndTheToolCallsOfTheAnsweringModelAreNeverTouchedByTheReview() throws Exception {
        Shop s = shop();
        String ext = "g6-" + uniq();
        deepseek(req -> text("تمام يا فندم، ابعتلي الاسم والعنوان ورقم الموبايل"));   // short, no facts: not reviewed
        assertThat(ask(s, ext, "عايز " + s.name())).contains("ابعتلي");
        deepseek(req -> toolCall("prepare_order", Map.of("items", List.of(Map.of("slug", s.slug(), "quantity", 1)), "customerName", "كريم", "phone", "01023456789", "governorateCode", "CAI",
                "addressLine1", "شارع مكرم عبيد عمارة ٣ شقة ٧", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0)));
        deepseek(req -> text("ده الملخص"));
        glm(req -> text("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"رد المراجع المرفوض\"}"));   // would replace the order summary if it were used
        String reply = ask(s, ext, "انا كريم، القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧، ٠١٠٢٣٤٥٦٧٨٩، كاش. التوصيل امتى؟");
        assertThat(reply).contains("ملخص").doesNotContain("المراجع");
        assertThat(GLM_SEEN).as("a code-written summary is not reviewed at all").isEmpty();
        assertThat(jdbc.sql("SELECT (pending -> 'items' -> 0 ->> 'quantity')::int FROM chat.conversations WHERE external_id = :c AND channel = 'WEB'").param("c", ext).query(Integer.class).single()).isEqualTo(1);
        assertThat(DEEPSEEK_SEEN.stream().filter(r -> r.toString().contains("prepare_order")).count()).as("the order was prepared by one tool call").isGreaterThanOrEqualTo(1);
    }

    @Test
    void aCorrectionThatOffersASoldOutColourOrAnInventedSizeIsDroppedAndAGoodOneIsKept() throws Exception {
        Shop s = shirtWithColours();
        String draft = "القميص القطن بـ 150 جنيه، متاح أبيض مقاس M وL. تحب أحجزهولك؟";
        for (String bad : new String[] {"القميص القطن بـ 150 جنيه يا فندم، متاح أبيض مقاس M وL، وأسود مقاس M. تحب تحجز واحد؟", "القميص القطن بـ 150 جنيه يا فندم، متوفر أبيض وأسود في مقاسات M و L. تحب أحجزهولك؟"}) {
            deepseek(req -> toolCall("search_products", Map.of("query", s.name())));
            deepseek(req -> text(draft));
            glm(req -> text("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"" + bad + "\"}"));
            assertThat(ask(s, "g7-" + uniq(), "بكام " + s.name() + "؟")).as(bad).isEqualTo(draft);
        }
        deepseek(req -> toolCall("search_products", Map.of("query", s.name())));
        deepseek(req -> text("القميص القطن بـ 150 جنيه يا باشا، متاح أبيض مقاس M وL. تحب أحجزهولك؟"));
        glm(req -> text("{\"ok\":false,\"problems\":[\"casual\"],\"reply\":\"القميص القطن بـ 150 جنيه يا فندم، متاح أبيض مقاس M وL. تحب أحجزهولك؟\"}"));
        assertThat(ask(s, "g7c-" + uniq(), "بكام " + s.name() + "؟")).as("a correction that keeps the options and fixes the address is used").isEqualTo(draft.replace("جنيه،", "جنيه يا فندم،"));
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * app.ai.rules.skip-unchecked-rewrite (default OFF). A draft written without a tool call that describes colours the shop really has, at the shop's real price, is kept ON (no rewrite round);
 * OFF it is rewritten as always; and a draft with a wrong price or an invented colour is rewritten even ON (a legitimate correction is never skipped).
 */
class SkipUncheckedRewriteIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @AfterEach
    void off() { ReflectionTestUtils.setField(chat, "skipUncheckedRewrite", false); }

    Tenant sneakers() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        StringBuilder variants = new StringBuilder();
        String[] colours = {"أسود", "كحلي", "أبيض"};
        for (int i = 0; i < colours.length; i++) {
            if (i > 0) variants.append(',');
            variants.append("{\"sku\":\"K-%s\",\"priceMinor\":69000,\"optionValues\":{\"اللون\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":5}]}".formatted(uniq(), colours[i], branch));
        }
        onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"taxonomySlug\":\"handbags\",\"audience\":\"ALL\",\"name\":\"كوتشي كانفاس\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أسود\",\"كحلي\",\"أبيض\"]}],\"variants\":[%s]}".formatted(variants))
                .andExpect(status().isCreated());
        return t;
    }

    /** The reply to «price and colours?» when the model first answers `draft` with no tool call, and `rewrite` to the system check if one comes. */
    String reply(Tenant t, String draft, String rewrite) throws Exception {
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(draft));
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model(rewrite));
        String body = onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"كوتشي كانفاس سعره كام وألوانه ايه؟\"}".formatted(UUID.randomUUID())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.reply");
    }

    static final String GOOD = "كوتشي كانفاس سعره ٦٩٠ جنيه، متاح بألوان أسود وكحلي وأبيض.";
    static final String REWRITTEN = "REWRITE-MARK كوتشي كانفاس بـ ٦٩٠ جنيه، أسود وكحلي وأبيض.";

    @Test
    void offByDefaultAGroundedDraftIsStillRewrittenAsBefore() throws Exception {
        assertThat((boolean) ReflectionTestUtils.getField(chat, "skipUncheckedRewrite")).isFalse();
        String r = reply(sneakers(), GOOD, REWRITTEN);
        assertThat(r).contains("REWRITE-MARK");
    }

    @Test
    void onAGroundedDraftIsKeptWithoutARewrite() throws Exception {
        ReflectionTestUtils.setField(chat, "skipUncheckedRewrite", true);
        String r = reply(sneakers(), GOOD, REWRITTEN);
        assertThat(r).doesNotContain("REWRITE-MARK").contains("٦٩٠");
        assertThat(FakeAiProvider.SCRIPT).as("the rewrite turns were never asked for").isNotEmpty();
    }

    @Test
    void onAWrongPriceIsStillRewritten() throws Exception {
        ReflectionTestUtils.setField(chat, "skipUncheckedRewrite", true);
        String r = reply(sneakers(), "كوتشي كانفاس سعره ٦٥٠ جنيه، متاح بألوان أسود وكحلي وأبيض.", REWRITTEN);
        assertThat(r).contains("REWRITE-MARK");
    }

    @Test
    void onAnInventedColourIsStillRewritten() throws Exception {
        ReflectionTestUtils.setField(chat, "skipUncheckedRewrite", true);
        String r = reply(sneakers(), "كوتشي كانفاس سعره ٦٩٠ جنيه، متاح بألوان أحمر وأسود وكحلي.", REWRITTEN);
        assertThat(r).contains("REWRITE-MARK");
    }

    @Test
    void onADraftWithAnotherRuleHitIsStillRewritten() throws Exception {
        ReflectionTestUtils.setField(chat, "skipUncheckedRewrite", true);
        // grounded facts, but it also tells the customer it will confirm later (STALL): a different rule fired, so the rewrite stays
        String r = reply(sneakers(), GOOD + " هتأكدلك وأرجعلك.", REWRITTEN);
        assertThat(r).contains("REWRITE-MARK");
    }
}

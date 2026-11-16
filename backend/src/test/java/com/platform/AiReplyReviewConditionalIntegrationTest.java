package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** app.ai.review.mode=conditional: a plain draft is not sent for a second model call, a risky one still is; and the reviewer is given the products the model itself was given. */
@TestPropertySource(properties = {"app.ai.review.enabled=true", "app.ai.review.mode=conditional", "app.ai.experiment.tenants=*"})
class AiReplyReviewConditionalIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    Tenant shop;

    UUID shop() {
        try { shop = onboard("STORE"); } catch (Exception e) { throw new IllegalStateException(e); }
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
    }

    @Test
    void aPlainDraftSkipsTheReviewAndARiskyOneStillGetsIt() {
        UUID id = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة، تحبي لون إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"x\"],\"reply\":\"SHOULD NOT BE USED\"}"));
        assertThat(chat.reply(id, "WEB", "c1-" + uniq(), null, "عندكم شنط؟").text()).isEqualTo("عندنا شنط جلد حلوة، تحبي لون إيه؟");
        assertThat(FakeAiProvider.SCRIPT).as("the review call was never made").hasSize(1);

        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد عليها ضمان سنة، تحبي لون إيه؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":false,\"problems\":[\"invented warranty\"],\"reply\":\"عندنا شنط جلد حلوة، تحبي لون إيه؟\"}"));
        assertThat(chat.reply(id, "WEB", "c2-" + uniq(), null, "عندكم شنط تانية؟").text()).isEqualTo("عندنا شنط جلد حلوة، تحبي لون إيه؟");
    }

    @Test
    void theReviewerIsShownTheProductsFoundForTheMessageNotOnlyToolResults() throws Exception {
        UUID id = shop();
        String name = "شنطة رياضة " + uniq();
        String branch = JsonPath.read(onHost(shop.host(), shop.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"handbags","audience":"WOMEN","name":"%1$s","options":[{"name":"اللون","values":["أسود"]}],
                 "variants":[{"sku":"R-%2$s","priceMinor":62000,"optionValues":{"اللون":"أسود"},"stock":[{"branchId":"%3$s","quantity":5}]}]}
                """.formatted(name, uniq(), branch)).andExpect(status().isCreated());
        AtomicReference<String> reviewerInput = new AtomicReference<>("");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا " + name + " ، تحبي أحجزهالك؟ ضمان سنة"));   // «ضمان» keeps the draft in the review
        FakeAiProvider.SCRIPT.add(turns -> { reviewerInput.set(turns.get(0).text()); return Turn.model("{\"ok\":true}"); });
        chat.reply(id, "WEB", "c3-" + uniq(), null, name + " بكام؟");
        assertThat(reviewerInput.get()).contains("SHOP DATA").contains("PRODUCTS FOR THE CUSTOMER'S NEWEST MESSAGE").contains(name).contains("620");
    }
}

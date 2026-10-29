package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.marketing.AdsService;
import com.platform.marketing.SocialService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** AI posts to the connected Facebook page / Instagram account, and ad campaigns with AI-suggested settings. Meta and the AI are faked. */
class MarketingIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired SocialService social;
    @Autowired AdsService ads;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); FakeImageGenerator.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String productId) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Linen Shirt %s","brand":"Acme","description":"Soft breathable linen shirt","variants":[{"sku":"S-%s","priceMinor":45000,"compareAtPriceMinor":60000,"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        return new Shop(t, JsonPath.read(p, "$.id"));
    }

    void connectPage(Tenant t, String platform, String id) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"%s\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(platform, id)).andExpect(status().isCreated());
    }

    String ai(String caption) { return "{\"caption\":\"" + caption + "\",\"hashtags\":[\"linen\",\"#summer\"],\"imagePrompt\":\"A linen shirt on a hanger\"}"; }

    @Test
    void aProductPostGetsAiWordsAndPictureAndGoesToFacebookAndInstagram() throws Exception {
        Shop s = shop();
        connectPage(s.t(), "FACEBOOK", "9" + System.nanoTime());
        connectPage(s.t(), "INSTAGRAM", "8" + System.nanoTime());
        FakeMetaGraph.SENT.clear();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/marketing/overview", null).andExpect(jsonPath("$.channels.length()").value(2)).andExpect(jsonPath("$.imagesAvailable").value(true));

        FakeAiProvider.SCRIPT.add(turns -> Turn.model(ai("Summer is here! Our linen shirt is light and cool. Order now")));
        String post = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/draft", "{\"kind\":\"PRODUCT\",\"productId\":\"" + s.productId() + "\",\"language\":\"en\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.channels.length()").value(2)));
        assertThat((String) JsonPath.read(post, "$.caption")).contains("Summer is here").contains("#linen").contains("#summer");
        assertThat((String) JsonPath.read(post, "$.imageUrl")).matches(".*/(media|api/v1/files)/.*");
        assertThat(FakeImageGenerator.PROMPTS).containsExactly("A linen shirt on a hanger");
        assertThat(FakeAiProvider.lastSystem).contains("English").contains("Egypt");
        String id = JsonPath.read(post, "$.id");

        // the owner edits the words, then posts
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/marketing/posts/" + id, "{\"caption\":\"Edited by the owner\"}").andExpect(jsonPath("$.caption").value("Edited by the owner"));
        String done = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/" + id + "/publish", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED")));
        assertThat((List<Boolean>) JsonPath.read(done, "$.results[*].ok")).containsOnly(true);
        var paths = FakeMetaGraph.SENT.stream().map(FakeMetaGraph.Sent::path).toList();
        assertThat(paths).anyMatch(p -> p.endsWith("/photos")).anyMatch(p -> p.endsWith("/media")).anyMatch(p -> p.endsWith("/media_publish"));
        var photo = FakeMetaGraph.SENT.stream().filter(x -> x.path().endsWith("/photos")).findFirst().orElseThrow();
        assertThat((String) photo.body().get("message")).startsWith("Edited by the owner").contains("/products/");   // the product link is added for Facebook
        var media = FakeMetaGraph.SENT.stream().filter(x -> x.path().endsWith("/media")).findFirst().orElseThrow();
        assertThat((String) media.body().get("image_url")).matches(".*/(media|api/v1/files)/.*");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/marketing/posts/" + id, "{\"caption\":\"x\"}").andExpect(status().isConflict());   // a published post is locked
    }

    @Test
    void withoutTheAiTheWordsFallBackToATemplateAndInstagramNeedsAPicture() throws Exception {
        Shop s = shop();
        connectPage(s.t(), "INSTAGRAM", "7" + System.nanoTime());
        FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("down"); });
        FakeImageGenerator.FAIL = true;
        String post = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/draft", "{\"kind\":\"PRODUCT\",\"productId\":\"" + s.productId() + "\",\"language\":\"ar\"}").andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(post, "$.caption")).contains("Linen Shirt").contains("450");   // template with the name and the price
        String id = JsonPath.read(post, "$.id");
        assertThat((Object) JsonPath.read(post, "$.imageUrl")).isNull();
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/" + id + "/publish", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_REQUIRED"));
        FakeImageGenerator.FAIL = false;
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/" + id + "/regenerate", "{\"what\":\"image\"}").andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isNotEmpty());
        assertThat(FakeImageGenerator.HAD_REFERENCE).isFalse();   // the product has no uploaded photo here
    }

    @Test
    void aShopWidePostScheduledPostsAndFailuresAreReportedPerChannel() throws Exception {
        Shop s = shop();
        connectPage(s.t(), "FACEBOOK", "6" + System.nanoTime());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(ai("Welcome to our shop")));
        String post = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/draft", "{\"kind\":\"SHOP\",\"withImage\":false}").andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("SHOP")));
        String id = JsonPath.read(post, "$.id");
        assertThat(FakeAiProvider.lastSystem).contains("Egypt");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/" + id + "/schedule", "{\"at\":\"" + Instant.now().minusSeconds(3600) + "\"}").andExpect(status().isBadRequest());
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/posts/" + id + "/schedule", "{\"at\":\"" + Instant.now().plusSeconds(3600) + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        jdbc.sql("UPDATE marketing.posts SET scheduled_at = now() - interval '1 minute' WHERE id = CAST(:i AS uuid)").param("i", id).update();   // the time comes
        for (int i = 0; i < 20 && jdbc.sql("SELECT status FROM marketing.posts WHERE id = CAST(:i AS uuid)").param("i", id).query(String.class).single().equals("SCHEDULED"); i++)
            social.runDueNow();   // a reused test database may hold older due posts: each run takes 10
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/marketing/posts/" + id, null).andExpect(jsonPath("$.status").value("POSTED"));
        assertThat(FakeMetaGraph.SENT.stream().anyMatch(x -> x.path().endsWith("/feed"))).isTrue();   // no picture: a text post

        // a page whose token cannot post: reported, not thrown
        Shop s2 = shop();
        jdbc.sql("INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc) SELECT t.id, 'FACEBOOK', :e, 'Locked page', :tk FROM core.tenants t WHERE t.slug = :s")
                .param("e", "5" + System.nanoTime()).param("tk", crypto("BADPOST-token-0123456789")).param("s", s2.t().slug()).update();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(ai("Hello")));
        String p2 = ok(onHost(s2.t().host(), s2.t().access(), "POST", "/api/v1/store/marketing/posts/draft", "{\"kind\":\"SHOP\",\"withImage\":false}").andExpect(status().isCreated()));
        String res = ok(onHost(s2.t().host(), s2.t().access(), "POST", "/api/v1/store/marketing/posts/" + JsonPath.read(p2, "$.id") + "/publish", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")));
        assertThat((String) JsonPath.read(res, "$.results[0].error")).contains("pages_manage_posts");
        // another shop cannot read this post
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/marketing/posts/" + JsonPath.read(p2, "$.id"), null).andExpect(status().isNotFound());
    }

    @Autowired com.platform.chat.TokenCrypto tokenCrypto;

    String crypto(String token) { return tokenCrypto.encrypt(token); }

    @Test
    void anAdCampaignIsSuggestedBuiltPausedAndLaunchedOnPurpose() throws Exception {
        Shop s = shop();
        connectPage(s.t(), "FACEBOOK", "4" + System.nanoTime());
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", s.t().slug()).query(UUID.class).single();
        Map<String, Object> acct = ads.connectAccount(tenant, null, "act_777", "Shop ad account", "USER-TOKEN-0123456789012345", "EGP");
        String account = acct.get("id").toString();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/marketing/overview", null).andExpect(jsonPath("$.adAccounts.length()").value(1));

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"objective\":\"OUTCOME_TRAFFIC\",\"dailyBudgetMinor\":15000,\"durationDays\":10,\"ageMin\":22,\"ageMax\":45,\"genders\":[\"female\"],\"interests\":[\"Shopping\",\"Fashion\"],\"headline\":\"Summer linen\",\"primaryText\":\"Light, cool and ready to ship\",\"reasoning\":\"Women 22-45 buy linen in summer\"}"));
        String sug = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/suggest", "{\"accountId\":\"" + account + "\",\"productId\":\"" + s.productId() + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAi").value(true)).andExpect(jsonPath("$.durationDays").value(10)).andExpect(jsonPath("$.countries[0]").value("EG")));
        assertThat((Number) JsonPath.read(sug, "$.dailyBudgetMinor")).isEqualTo(15000);
        // with the AI down the owner still gets sensible defaults
        FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("down"); });
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/suggest", "{\"accountId\":\"" + account + "\"}").andExpect(jsonPath("$.fromAi").value(false)).andExpect(jsonPath("$.dailyBudgetMinor").isNumber());

        FakeMetaGraph.SENT.clear();
        String body = """
                {"accountId":"%s","productId":"%s","name":"Summer linen","objective":"OUTCOME_TRAFFIC","dailyBudgetMinor":15000,"durationDays":10,"countries":["EG"],"ageMin":22,"ageMax":45,"genders":["female"],
                 "interests":["Shopping"],"headline":"Summer linen","primaryText":"Light and cool","cta":"SHOP_NOW","imageUrl":"https://cdn.example.com/a.jpg"}
                """.formatted(account, s.productId());
        String camp = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns", body).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PAUSED")));   // never spends before the owner launches
        var paths = FakeMetaGraph.SENT.stream().map(FakeMetaGraph.Sent::path).toList();
        assertThat(paths).anyMatch(p -> p.equals("act_777/campaigns")).anyMatch(p -> p.equals("act_777/adsets")).anyMatch(p -> p.equals("act_777/adcreatives")).anyMatch(p -> p.equals("act_777/ads"));
        var adset = FakeMetaGraph.SENT.stream().filter(x -> x.path().equals("act_777/adsets")).findFirst().orElseThrow();
        assertThat(adset.body().get("daily_budget")).isEqualTo(15000L);
        assertThat(adset.body().get("targeting").toString()).contains("EG").contains("6003").contains("genders=[2]");
        assertThat(adset.body().get("status")).isEqualTo("PAUSED");
        String id = JsonPath.read(camp, "$.id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/" + id + "/launch", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/" + id + "/refresh", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.insights.clicks").value("45")).andExpect(jsonPath("$.insights.spend").value("31.50"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns/" + id + "/pause", "{}").andExpect(jsonPath("$.status").value("PAUSED"));

        // validation and isolation
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/marketing/campaigns", body.replace("15000", "100")).andExpect(status().isBadRequest());
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/marketing/campaigns/" + id, null).andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/marketing/campaigns", body).andExpect(status().isNotFound());   // not their ad account
    }
}

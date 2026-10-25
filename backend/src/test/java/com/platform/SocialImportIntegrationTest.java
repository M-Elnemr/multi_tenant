package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** "Start my shop from my Facebook page": only the connected page, posts become DRAFT products with their pictures, prices only when the post states one. */
class SocialImportIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); FakeRemoteImages.FETCHED.clear(); }

    String page() { return String.valueOf(100000000000L + (System.nanoTime() % 899999999999L)); }

    void connect(Tenant t, String platform, String id) throws Exception {
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", "{\"platform\":\"%s\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(platform, id)).andExpect(status().isCreated());
    }

    @Test
    void postsBecomeDraftProductsWithPicturesAndOnlyTheOwnersOwnLinkIsAccepted() throws Exception {
        Tenant shop = onboard("STORE");
        // nothing connected yet
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/builder/social", "{\"platform\":\"FACEBOOK\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_CHANNEL"));
        connect(shop, "FACEBOOK", page());
        onHost(shop.host(), shop.access(), "GET", "/api/v1/store/builder/social", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].platform").value("FACEBOOK"));

        FakeMetaGraph.FB_POSTS = List.of(
                Map.of("message", "Soft cotton t-shirt, 250 EGP. Order now!", "full_picture", "https://scontent.xx.fbcdn.net/a.jpg"),
                Map.of("message", "Linen shirt in 3 colours, message us for the price", "full_picture", "https://scontent.xx.fbcdn.net/b.jpg"),
                Map.of("message", "Happy Eid everyone!"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"shortDescription\":\"Cotton basics\",\"about\":\"From Cairo\",\"template\":\"souq\",\"products\":["
                + "{\"name\":\"Cotton t-shirt\",\"description\":\"Soft cotton\",\"price\":250,\"post\":1},{\"name\":\"Linen shirt\",\"description\":\"3 colours\",\"price\":null,\"post\":2}]}"));

        // a link that is not the connected page is refused, and nothing is created
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/builder/social", "{\"platform\":\"FACEBOOK\",\"url\":\"https://www.facebook.com/someone-else\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_YOUR_ACCOUNT"));
        onHost(shop.host(), shop.access(), "POST", "/api/v1/store/builder/social", "{\"platform\":\"FACEBOOK\",\"url\":\"https://evil.example/myshop\"}").andExpect(status().isBadRequest());
        assertThat(FakeRemoteImages.FETCHED).isEmpty();

        String r = onHost(shop.host(), shop.access(), "POST", "/api/v1/store/builder/social", "{\"platform\":\"FACEBOOK\",\"url\":\"https://www.facebook.com/MyShop\",\"language\":\"en\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.products.length()").value(2)).andExpect(jsonPath("$.withPicture").value(2)).andExpect(jsonPath("$.needsPrice.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(r, "$.source")).isEqualTo("FACEBOOK");
        assertThat(FakeRemoteImages.FETCHED).containsExactly("https://scontent.xx.fbcdn.net/a.jpg", "https://scontent.xx.fbcdn.net/b.jpg");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND status = 'DRAFT'").param("t", tenant).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.product_media WHERE tenant_id = :t").param("t", tenant).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id = :t AND feature = 'SOCIAL_IMPORT'").param("t", tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void linksAreMatchedToTheConnectedAccountOnly() {
        var fb = Map.<String, Object>of("id", "123456", "username", "MyShop");
        var matches = (java.util.function.BiPredicate<String, String>) (platform, link) -> com.platform.commerce.SocialImportService.matches(platform, link, "123456", fb);
        assertThat(matches.test("FACEBOOK", "https://www.facebook.com/myshop")).isTrue();
        assertThat(matches.test("FACEBOOK", "facebook.com/MyShop/")).isTrue();
        assertThat(matches.test("FACEBOOK", "https://m.facebook.com/profile.php?id=123456")).isTrue();
        assertThat(matches.test("FACEBOOK", "https://www.facebook.com/people/My-Shop/123456/")).isTrue();
        assertThat(matches.test("FACEBOOK", "https://www.facebook.com/other")).isFalse();
        assertThat(matches.test("FACEBOOK", "https://facebook.com.evil.example/myshop")).isFalse();
        assertThat(matches.test("FACEBOOK", "https://www.instagram.com/myshop")).isFalse();   // wrong network for a Facebook import
        assertThat(matches.test("INSTAGRAM", "https://instagram.com/MyShop?igsh=abc")).isTrue();
        assertThat(matches.test("INSTAGRAM", "http://instagram.com/myshop")).isFalse();   // https only
        assertThat(matches.test("FACEBOOK", "javascript:alert(1)")).isFalse();
    }
}

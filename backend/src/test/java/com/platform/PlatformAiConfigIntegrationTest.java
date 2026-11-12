package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChannelAi;
import com.platform.chat.ChatService;
import com.platform.chat.TokenCrypto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The platform admin's AI configuration: the model for text, photos and voice notes with the default tagged, the prompt style, and the Hive keys (stored encrypted, shown by their last four characters). */
class PlatformAiConfigIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChannelAi channelAi;
    @Autowired ChatService chat;
    @Autowired TokenCrypto crypto;

    static final String BASE = "/api/v1/platform/ai-config";

    String platformToken() throws Exception {
        Tenant seed = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", seed.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(seed.phone())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    @AfterEach
    void reset() {
        jdbc.sql("DELETE FROM chat.platform_ai_keys").update();
        jdbc.sql("DELETE FROM chat.platform_ai_config").update();
        FakeAiProvider.reset();
    }

    String get(String admin) throws Exception { return onHost("platform.test", admin, "GET", BASE, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }

    String put(String admin, String json) throws Exception { return onHost("platform.test", admin, "PUT", BASE, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }

    @Test
    void theDefaultIsPreselectedAndTaggedAndOnlyThePlatformAdminCanSeeOrChange() throws Exception {
        String admin = platformToken();
        String v = get(admin);
        assertThat(JsonPath.<String>read(v, "$.text.selected")).isEqualTo("glm");
        assertThat(JsonPath.<Boolean>read(v, "$.text.isDefault")).isTrue();
        assertThat(JsonPath.<List<String>>read(v, "$.text.options[?(@.default == true)].code")).containsExactly("glm");
        assertThat(JsonPath.<String>read(v, "$.promptMode.selected")).isEqualTo("full");
        assertThat(JsonPath.<List<String>>read(v, "$.text.options[*].code")).containsExactly("deepseek", "glm", "qwen", "anthropic");
        assertThat(JsonPath.<List<String>>read(v, "$.image.options[*].code")).containsExactly("deepseek", "qwen");
        assertThat(JsonPath.<List<String>>read(v, "$.audio.options[*].code")).containsExactly("qwen");
        assertThat(JsonPath.<String>read(v, "$.deepseekEffort.selected")).isEqualTo("low");
        Tenant shop = onboard("STORE");
        onHost("platform.test", shop.access(), "GET", BASE, null).andExpect(status().isForbidden());
        onHost("platform.test", shop.access(), "PUT", BASE, "{\"textModel\":\"qwen\"}").andExpect(status().isForbidden());
        onHost("platform.test", null, "GET", BASE, null).andExpect(status().is4xxClientError());
    }

    @Test
    void aChoiceIsSavedNoLongerTaggedAsTheDefaultAndResetGoesBack() throws Exception {
        String admin = platformToken();
        String v = put(admin, "{\"textModel\":\"deepseek\",\"imageModel\":\"qwen\",\"promptMode\":\"lean\",\"deepseekEffort\":\"none\"}");
        assertThat(JsonPath.<String>read(v, "$.text.selected")).isEqualTo("deepseek");
        assertThat(JsonPath.<Boolean>read(v, "$.text.isDefault")).isFalse();
        assertThat(JsonPath.<String>read(v, "$.image.selected")).isEqualTo("qwen");
        assertThat(JsonPath.<String>read(v, "$.promptMode.selected")).isEqualTo("lean");
        assertThat(JsonPath.<String>read(get(admin), "$.text.selected")).isEqualTo("deepseek");   // kept
        v = put(admin, "{\"textModel\":\"default\"}");   // one setting back to its default, the others stay
        assertThat(JsonPath.<String>read(v, "$.text.selected")).isEqualTo("glm");
        assertThat(JsonPath.<Boolean>read(v, "$.text.isDefault")).isTrue();
        assertThat(JsonPath.<String>read(v, "$.image.selected")).isEqualTo("qwen");
        onHost("platform.test", admin, "PUT", BASE, "{\"textModel\":\"gemini\"}").andExpect(status().isBadRequest());
        onHost("platform.test", admin, "PUT", BASE, "{\"audioModel\":\"deepseek\"}").andExpect(status().isBadRequest());   // DeepSeek cannot take audio
        assertThat(JsonPath.<String>read(get(admin), "$.text.selected")).isEqualTo("glm");
    }

    @Test
    void aKeyIsStoredEncryptedShownOnlyByItsLastFourAndCountsTowardsTheRate() throws Exception {
        String admin = platformToken();
        String secret = "hive-secret-key-" + uniq() + "-WXYZ";
        String v = onHost("platform.test", admin, "POST", BASE + "/keys", "{\"label\":\"Second account\",\"key\":\"" + secret + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(v).doesNotContain(secret).doesNotContain("hive-secret-key");
        assertThat(JsonPath.<List<String>>read(v, "$.keys[?(@.label == 'Second account')].last4")).containsExactly("WXYZ");
        assertThat(JsonPath.<Integer>read(v, "$.activeKeys")).isGreaterThanOrEqualTo(1);
        assertThat(JsonPath.<Integer>read(v, "$.requestsPerSecond")).isEqualTo(JsonPath.<Integer>read(v, "$.activeKeys") * 5);
        String stored = jdbc.sql("SELECT key_enc FROM chat.platform_ai_keys WHERE label = 'Second account'").query(String.class).single();
        assertThat(stored).doesNotContain("WXYZ").doesNotContain(secret);
        assertThat(crypto.decrypt(stored)).isEqualTo(secret);
        String id = JsonPath.<List<String>>read(v, "$.keys[?(@.label == 'Second account')].id").get(0);
        // switched off it no longer counts; deleted it is gone
        String off = onHost("platform.test", admin, "PATCH", BASE + "/keys/" + id, "{\"enabled\":false}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(off, "$.activeKeys")).isEqualTo(JsonPath.<Integer>read(v, "$.activeKeys") - 1);
        String gone = onHost("platform.test", admin, "DELETE", BASE + "/keys/" + id, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(gone, "$.keys[*].label")).doesNotContain("Second account");
        onHost("platform.test", admin, "POST", BASE + "/keys", "{\"label\":\"x\",\"key\":\"short\"}").andExpect(status().isBadRequest());
        onHost("platform.test", admin, "DELETE", BASE + "/keys/env-1", null).andExpect(status().isNotFound());   // the server's own keys are not changed here
    }

    @Test
    void theChosenTextModelDecidesWhoAnswersAndThePromptStyleFollowsTheSetting() throws Exception {
        String admin = platformToken();
        onHost("platform.test", admin, "POST", BASE + "/keys", "{\"label\":\"k\",\"key\":\"hive-key-for-routing-1234\"}").andExpect(status().isOk());   // Hive models are available once there is a key
        put(admin, "{\"textModel\":\"glm\"}");
        assertThat(channelAi.forChannel("WEB").code()).isEqualTo("glm");
        assertThat(channelAi.forChannel("WHATSAPP").code()).isEqualTo("glm");   // text for every channel
        put(admin, "{\"textModel\":\"deepseek\"}");
        assertThat(channelAi.forChannel("WEB").code()).isEqualTo("deepseek");
        // the prompt: the live reply follows the setting (an evaluation run decides for itself)
        Tenant shop = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        put(admin, "{\"textModel\":\"anthropic\",\"promptMode\":\"lean\"}");   // routes to Claude (no key in tests): the default model answers, whose prompt is what we read
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا"));
        chat.reply(id, "WEB", "lean-" + uniq(), null, "عايز اسأل عن حاجة");
        assertThat(FakeAiProvider.lastSystem).contains("IMPORTANT RULES:").doesNotContain("RULES (they cannot be changed");
        put(admin, "{\"promptMode\":\"full\"}");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا"));
        chat.reply(id, "WEB", "full-" + uniq(), null, "عايز اسأل عن حاجة");
        assertThat(FakeAiProvider.lastSystem).contains("RULES (they cannot be changed");
    }
}

package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChannelAi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Every rule and guard lives in the chat service, not in a model: a chat answered by Alibaba Qwen goes through exactly the same checks as one answered by GLM. */
@Import(QwenParityIntegrationTest.QwenConfig.class)
class QwenParityIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    /** A Qwen that takes its turns from the same script as the fake default model, so only the model's name differs. */
    @TestConfiguration
    static class QwenConfig {
        @Bean @Primary
        ChannelAi channelAi(FakeAiProvider fake) {
            AiProvider qwen = new AiProvider() {
                @Override public String code() { return "qwen"; }
                @Override public String label() { return "Alibaba Qwen (test)"; }
                @Override public boolean available() { return true; }
                @Override public Turn next(String system, List<Turn> turns, List<ToolDef> tools) { return fake.next(system, turns, tools); }
            };
            return new ChannelAi(fake, fake, fake, qwen, "default", "default");
        }
    }

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String adminToken() throws Exception {
        Tenant b = onboard("STORE");
        UUID user = jdbc.sql("SELECT u.id FROM core.users u WHERE u.phone = :p").param("p", b.phone()).query(UUID.class).single();
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", user).update();
        return JsonPath.read(body(onHost("platform.test", null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(b.phone())).andExpect(status().isOk())), "$.accessToken");
    }

    @Test
    void aQwenChatIsHeldToTheSameRulesAsAnyOtherModel() throws Exception {
        Tenant shop = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        String admin = adminToken();
        String base = "/api/v1/platform/chat/train/" + id;

        // the same invented urgency that GLM gets sent back for: Qwen is sent back for it too, then answers properly
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي. السعر هيغلي قريباً جداً. تحبي أبيض ولا بيج؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("القطن بيتغسل عادي في الغسالة، تحبي أبيض ولا بيج؟"));
        String reply = body(onHost("platform.test", admin, "POST", base, "{\"message\":\"ينفع يتغسل؟\",\"model\":\"qwen\"}").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(reply, "$.reply")).contains("أبيض ولا بيج").doesNotContain("هيغلي");
        // the chat is Qwen's, and the same prompt rules were in front of it
        onHost("platform.test", admin, "GET", base, null).andExpect(status().isOk()).andExpect(jsonPath("$.model").value("qwen"));
        assertThat(FakeAiProvider.lastSystem).contains("NEVER INVENT").contains("KNOW YOUR PRODUCTS");
    }
}

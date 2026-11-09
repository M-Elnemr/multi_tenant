package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shops pay the AI with points: 1 EGP (100 piasters) per block of 10 AI replies; the AI answers only on the channels the
 * owner picked and only while there are points. The model is scripted (it answers "ok").
 */
class AiPointsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID id(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String send(Tenant t, String conv, String text) throws Exception {
        return onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachTenAiRepliesCostOneEgpAndTheAiPausesWithoutPoints() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = id(t);
        assertThat(wallet.balance(tenant)).isEqualTo(10_000L);   // 100 free points
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 250 WHERE tenant_id = :t").param("t", tenant).update();   // 2.5 EGP

        String conv = UUID.randomUUID().toString();
        for (int i = 1; i <= 10; i++) assertThat((String) JsonPath.read(send(t, conv, "question number " + i + " about the products"), "$.reply")).isEqualTo("ok");
        assertThat(wallet.balance(tenant)).isEqualTo(150L);   // replies 1-10 = one block = 1 EGP
        assertThat(jdbc.sql("SELECT ai_msgs FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", tenant).param("e", conv).query(Integer.class).single()).isEqualTo(10);

        // the 11th AI reply opens the second block: another 1 EGP
        assertThat((String) JsonPath.read(send(t, conv, "question number 11 about the products"), "$.reply")).isEqualTo("ok");
        assertThat(wallet.balance(tenant)).isEqualTo(50L);
        for (int i = 12; i <= 20; i++) assertThat((String) JsonPath.read(send(t, conv, "question number " + i + " about the products"), "$.reply")).isEqualTo("ok");
        assertThat(wallet.balance(tenant)).isEqualTo(50L);   // 11-20 are inside the second block
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND kind = 'AI' AND amount_minor = -100").param("t", tenant).query(Long.class).single()).isEqualTo(2);

        // the 21st opens a third block: half an EGP left is not enough, so the AI stays quiet and the chat goes to the team
        String paused = JsonPath.read(send(t, conv, "question number 21 about the products"), "$.reply");
        assertThat(paused).isNotEqualTo("ok");
        assertThat(wallet.balance(tenant)).isEqualTo(50L);
        assertThat(jdbc.sql("SELECT ai_msgs FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", tenant).param("e", conv).query(Integer.class).single()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT needs_human FROM chat.conversations WHERE tenant_id = :t AND external_id = :e").param("t", tenant).param("e", conv).query(Boolean.class).single()).isTrue();
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(jsonPath("$.enabled").value(false));   // no chat button without points

        // a top-up brings it back
        wallet.credit(tenant, 1_000, "TOPUP", null, "test", null);
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(jsonPath("$.enabled").value(true));
        String conv2 = UUID.randomUUID().toString();
        assertThat((String) JsonPath.read(send(t, conv2, "do you have this in blue please"), "$.reply")).isEqualTo("ok");
        assertThat(wallet.balance(tenant)).isEqualTo(950L);
    }

    @Test
    void theOwnerPicksTheChannelsTheAiAnswersOn() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/settings", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(4)).andExpect(jsonPath("$.pointsMinor").value(10_000)).andExpect(jsonPath("$.conversationPriceMinor").value(100)).andExpect(jsonPath("$.conversationReplies").value(10));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"channels\":[\"NOPE\"]}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"channels\":[\"WHATSAPP\",\"INSTAGRAM\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(2)).andExpect(jsonPath("$.channels[0]").value("WHATSAPP"));
        // the website is not picked: no chat button and no AI reply there
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(jsonPath("$.enabled").value(false));
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"hello there\"}".formatted(UUID.randomUUID())).andExpect(status().isServiceUnavailable());
        // saving other settings keeps the channels
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"tone\":\"FORMAL\"}").andExpect(jsonPath("$.channels.length()").value(2));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/settings", "{\"channels\":[\"WEB\"]}").andExpect(status().isOk());
        onHost(t.host(), null, "GET", "/api/v1/shop/chat/config", null).andExpect(jsonPath("$.enabled").value(true));
    }
}

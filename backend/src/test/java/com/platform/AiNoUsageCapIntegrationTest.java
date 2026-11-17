package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * No application-level daily or monthly AI usage cap exists any more (product decision 2026-11-17, docs/ai-usage-cap-removed.md): a shop keeps being answered however many AI messages it has
 * used this month or today, a plan limit on «ai_messages» does not block it, one shop never blocks another, and usage is still counted and attributed.
 */
class AiNoUsageCapIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() throws Exception {
        Tenant t = onboard("STORE");
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    Tenant tenant;

    UUID shopWithHost() throws Exception {
        tenant = onboard("STORE");
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", tenant.slug()).query(UUID.class).single();
    }

    void setMonthUsage(UUID tenantId, long messages) {
        jdbc.sql("INSERT INTO chat.usage (tenant_id, month, ai_messages) VALUES (:t, :m, :n) ON CONFLICT (tenant_id, month) DO UPDATE SET ai_messages = :n")
                .param("t", tenantId).param("m", java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1))).param("n", messages).update();
    }

    long monthUsage(UUID tenantId) {
        return jdbc.sql("SELECT ai_messages FROM chat.usage WHERE tenant_id = :t AND month = :m").param("t", tenantId).param("m", java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1))).query(Long.class).optional().orElse(0L);
    }

    String ask(UUID id, String conv, String text, String answer) {
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(answer));
        return chat.reply(id, "WEB", conv, null, text).text();
    }

    @Test
    void aShopFarAboveTheFormerMonthlyCapIsStillAnsweredAndStillCounted() throws Exception {
        UUID id = shop();
        setMonthUsage(id, 5000);   // the former default cap
        assertThat(ask(id, "cap-a-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟")).isEqualTo("عندنا شنط جلد حلوة، تحب لون إيه؟");
        assertThat(monthUsage(id)).isEqualTo(5001);
        setMonthUsage(id, 1_000_000);
        assertThat(ask(id, "cap-b-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟")).doesNotContain("الحد الشهري").doesNotContain("limit");
        assertThat(monthUsage(id)).isEqualTo(1_000_001);
    }

    @Test
    void manyAiRepliesOnTheSameDayAreAllAnsweredWhateverTheCounterSays() throws Exception {
        UUID id = shop();
        setMonthUsage(id, 4990);
        for (int i = 0; i < 30; i++)   // crosses the former cap of 5000 inside one day
            assertThat(ask(id, "day-" + i + "-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟")).as("reply " + i).isEqualTo("عندنا شنط جلد حلوة، تحب لون إيه؟");
        assertThat(monthUsage(id)).isEqualTo(5020);
    }

    @Test
    void aPlanLimitOnAiMessagesDoesNotBlockTheShop() throws Exception {
        UUID id = shop();
        UUID plan = jdbc.sql("SELECT plan_id FROM billing.tenant_subscriptions WHERE tenant_id = :t").param("t", id).query(UUID.class).single();
        jdbc.sql("INSERT INTO billing.subscription_plan_features (plan_id, feature_key, enabled, limit_value) VALUES (:p, 'ai_messages', TRUE, 1) ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = TRUE, limit_value = 1").param("p", plan).update();
        try {
            setMonthUsage(id, 50);
            assertThat(ask(id, "plan-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟")).isEqualTo("عندنا شنط جلد حلوة، تحب لون إيه؟");
            assertThat(monthUsage(id)).isEqualTo(51);
        } finally {
            jdbc.sql("DELETE FROM billing.subscription_plan_features WHERE plan_id = :p AND feature_key = 'ai_messages'").param("p", plan).update();
        }
    }

    @Test
    void oneShopsUsageNeverBlocksAnotherAndEachShopIsCountedSeparately() throws Exception {
        UUID heavy = shop(), light = shop();
        setMonthUsage(heavy, 9_999_999);
        setMonthUsage(light, 3);
        ask(heavy, "iso-h-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟");
        ask(light, "iso-l-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟");
        assertThat(monthUsage(heavy)).isEqualTo(10_000_000);
        assertThat(monthUsage(light)).isEqualTo(4);
    }

    @Test
    void usageIsStillRecordedWithTokensAttributedToTheShopAndShownByTheUsageEndpoint() throws Exception {
        UUID id = shopWithHost();
        setMonthUsage(id, 7000);
        long before = jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id = :t").param("t", id).query(Long.class).single();
        ask(id, "track-" + uniq(), "عندكم شنط؟", "عندنا شنط جلد حلوة، تحب لون إيه؟");
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isEqualTo(before + 1);
        onHost(tenant.host(), tenant.access(), "GET", "/api/v1/store/ai/usage", null).andExpect(status().isOk()).andExpect(jsonPath("$.usedThisMonth").value(7001)).andExpect(jsonPath("$.monthlyCap").doesNotExist());
        assertThat(jdbc.sql("SELECT count(*) FROM chat.ai_usage_log WHERE tenant_id <> :t AND conversation_id IN (SELECT conversation_id FROM chat.ai_usage_log WHERE tenant_id = :t)").param("t", id).query(Long.class).single()).isZero();
    }
}

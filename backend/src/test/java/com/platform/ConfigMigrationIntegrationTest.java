package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V90 and V91 (the AI configuration tables and the clean-up of the website model picks), checked against the migrated database. */
class ConfigMigrationIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void v90CreatesTheTablesWithTheirConstraintsAndStartsEmptyOfSettings() {
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'chat' AND table_name IN ('platform_ai_config', 'platform_ai_keys')").query(Long.class).single()).isEqualTo(2L);
        jdbc.sql("DELETE FROM chat.platform_ai_config").update();
        jdbc.sql("INSERT INTO chat.platform_ai_config (id) VALUES (1)").update();   // every setting NULL = "use the default"
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("INSERT INTO chat.platform_ai_config (id) VALUES (2)").update()).as("a single row").isInstanceOf(Exception.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE chat.platform_ai_config SET prompt_mode = 'huge'").update()).isInstanceOf(Exception.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("INSERT INTO chat.platform_ai_keys (label, key_enc, last4, provider) VALUES ('x', 'y', 'zzzz', 'other')").update()).isInstanceOf(Exception.class);
        jdbc.sql("DELETE FROM chat.platform_ai_config").update();
    }

    @Test
    void v91ForgetsOnlyTheModelPicksOfWebConversations() throws Exception {
        Tenant t = onboard("STORE");
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        for (String[] c : new String[][] {{"WEB", "qwen"}, {"TEST", "glm"}, {"WHATSAPP", "deepseek"}})
            jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, ai_model) VALUES (:t, :c, :e, :m)").param("t", tenant).param("c", c[0]).param("e", "v91-" + c[0] + "-" + uniq()).param("m", c[1]).update();
        String sql = new String(new ClassPathResource("db/migration/V91__forget_web_model_picks.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.sql(sql.lines().filter(l -> !l.startsWith("--")).reduce("", (a, b) -> a + " " + b)).update();
        assertThat(jdbc.sql("SELECT ai_model FROM chat.conversations WHERE tenant_id = :t AND channel = 'WEB'").param("t", tenant).query(String.class).list()).containsOnlyNulls();
        assertThat(jdbc.sql("SELECT ai_model FROM chat.conversations WHERE tenant_id = :t AND channel = 'TEST'").param("t", tenant).query(String.class).single()).isEqualTo("glm");
        assertThat(jdbc.sql("SELECT ai_model FROM chat.conversations WHERE tenant_id = :t AND channel = 'WHATSAPP'").param("t", tenant).query(String.class).single()).isEqualTo("deepseek");
    }
}

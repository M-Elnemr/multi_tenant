package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A database that cannot be read for a moment must not flip every live reply to the defaults: the settings last read stay in force. */
class AiConfigServiceTest {
    @Test
    void whenTheDatabaseFailsTheLastSettingsReadStayAndOnlyAnEmptyStartUsesTheDefaults() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).query().listOfRows()).thenReturn(List.of(Map.of("text_model", "glm"))).thenThrow(new RuntimeException("db down"));
        var svc = new AiConfigService(jdbc, HiveKeyPool.of(), "deepseek", "deepseek", "qwen", "lean", "low", "https://hive.example/v3", "m");
        assertThat(svc.get().textModel()).isEqualTo("glm");
        AiConfigService.forgetCached();
        assertThat(svc.get().textModel()).as("the read fails: still the last settings, not the default").isEqualTo("glm");
        // never read at all: the defaults
        JdbcClient broken = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(broken.sql(org.mockito.ArgumentMatchers.anyString()).query().listOfRows()).thenThrow(new RuntimeException("db down"));
        var fresh = new AiConfigService(broken, HiveKeyPool.of(), "deepseek", "deepseek", "qwen", "lean", "low", "https://hive.example/v3", "m");
        assertThat(fresh.get().textModel()).isEqualTo("deepseek");
        assertThat(fresh.get().promptMode()).isEqualTo("lean");
    }
}

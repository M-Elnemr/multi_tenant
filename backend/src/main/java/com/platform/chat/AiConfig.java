package com.platform.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The platform's one default language model: GLM through Hive's OpenAI-compatible API. Every AI feature that has no model of its own (chat default, shop builder, translation, import, copilot, marketing text) uses it. */
@Configuration
class AiConfig {
    @Bean
    AiProvider defaultAi(@Value("${app.ai.glm.api-key:}") String key, @Value("${app.ai.glm.model:zai-org/glm-5.3-flash}") String model, @Value("${app.ai.glm.base-url:https://api-cdn.thehive.ai/api/v3}") String baseUrl,
                         @Value("${app.ai.glm.reasoning-effort:low}") String effort, @Value("${app.ai.glm.max-output-tokens:3000}") int maxOutput,
                         @Value("${app.ai.glm.temperature:}") String temperature, @Value("${app.ai.glm.max-rps:5}") int maxRps) {
        return new OpenAiProvider("glm", "GLM", key, model, effort, baseUrl, maxOutput, true, ChannelAi.withTemperature("", temperature), maxRps);
    }
}

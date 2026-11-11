package com.platform.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The platform's default language model: GLM through Hive's OpenAI-compatible API, or (AI_FEATURES=qwen) Alibaba's Qwen with GLM as its backup. Every AI feature that has no model of its own (shop builder, translation, import, copilot, marketing text) uses it; chat routing keeps plain GLM as its default and backup. */
@Configuration
class AiConfig {
    @Bean
    AiProvider defaultAi(@Value("${app.ai.glm.api-key:}") String key, @Value("${app.ai.glm.model:zai-org/glm-5.3-flash}") String model, @Value("${app.ai.glm.base-url:https://api-cdn.thehive.ai/api/v3}") String baseUrl,
                         @Value("${app.ai.glm.reasoning-effort:low}") String effort, @Value("${app.ai.glm.max-output-tokens:3000}") int maxOutput,
                         @Value("${app.ai.glm.temperature:}") String temperature, @Value("${app.ai.glm.max-rps:5}") int maxRps,
                         @Value("${app.ai.features:glm}") String features, @Value("${app.ai.qwen.api-key:}") String qwenKey, @Value("${app.ai.qwen.model:qwen3.7-flash}") String qwenModel,
                         @Value("${app.ai.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String qwenUrl, @Value("${app.ai.qwen.thinking:off}") String qwenThinking,
                         @Value("${app.ai.qwen.temperature:}") String qwenTemperature, @Value("${app.ai.qwen.max-rps:0}") int qwenRps, HiveKeyPool hive) {
        AiProvider glm = new OpenAiProvider("glm", "GLM", hive, model, () -> effort, baseUrl, maxOutput, true, ChannelAi.withTemperature("", temperature), maxRps);
        // AI_FEATURES=qwen: the features without a model of their own use Alibaba's Qwen first, GLM when Qwen fails
        if ("qwen".equalsIgnoreCase(features == null ? "" : features.trim()) && qwenKey != null && !qwenKey.isBlank()) {
            AiProvider qwen = new OpenAiProvider("qwen", "Alibaba Qwen", qwenKey, qwenModel, "", qwenUrl, maxOutput, true, ChannelAi.withTemperature(ChannelAi.qwenExtra(qwenThinking), qwenTemperature), qwenRps);
            return new FeatureAi(qwen, glm);
        }
        return glm;
    }
}

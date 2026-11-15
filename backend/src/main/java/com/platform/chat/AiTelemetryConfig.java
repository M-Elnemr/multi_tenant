package com.platform.chat;

import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads the optional Langfuse settings (all off by default; see docs/ai-operations-guide.md) and starts {@link AiTelemetry}. */
@Component
class AiTelemetryConfig {
    @Value("${app.ai.langfuse.enabled:false}") boolean enabled;
    @Value("${app.ai.langfuse.host:}") String host;
    @Value("${app.ai.langfuse.public-key:}") String publicKey;
    @Value("${app.ai.langfuse.secret-key:}") String secretKey;
    @Value("${app.ai.langfuse.id-salt:}") String salt;
    @Value("${app.ai.langfuse.capture-content:false}") boolean captureContent;
    @Value("${app.ai.langfuse.queue-size:2000}") int queueSize;
    @Value("${app.ai.langfuse.environment:}") String environment;

    @PostConstruct
    void start() {
        boolean on = AiTelemetry.configure(enabled, host, publicKey, secretKey, salt, captureContent, queueSize, environment);
        LoggerFactory.getLogger(AiTelemetryConfig.class).info("Langfuse telemetry: {}{}", on ? "ON" : "off", on && captureContent ? " (prompt/answer text captured, redacted)" : "");
    }
}

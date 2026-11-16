package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

/** Which name switches tracing off: the property is app.ai.trace.enabled, the environment variable is AI_TRACE, and the production compose file must pass it through to the container. */
class ReplyTraceConfigTest {
    static boolean resolve(Map<String, Object> environment) throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("environment", environment));
        env.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));   // the real file, with its ${AI_TRACE:true} placeholder
        return env.getProperty("app.ai.trace.enabled", Boolean.class);
    }

    @Test
    void theEnvironmentVariableAiTraceFalseSwitchesTheFeatureOffAndTheDefaultIsOn() throws Exception {
        assertThat(resolve(Map.of())).isTrue();
        assertThat(resolve(Map.of("AI_TRACE", "false"))).isFalse();
        assertThat(resolve(Map.of("AI_TRACE", "true"))).isTrue();
    }

    @Test
    void theComposeFileThatRunsProductionPassesAiTraceThroughToTheContainer() throws Exception {
        Path compose = Path.of("..", "docker-compose.test-slim.yml");
        assumeTrue(Files.exists(compose), "the compose file is next to the backend folder in the repository");
        String text = Files.readString(compose);
        assertThat(text).contains("AI_TRACE: ${AI_TRACE:-true}");   // without this line a server .env AI_TRACE=false never reaches the application
    }

    @Test
    void theDocumentationNamesTheOneVariable() throws Exception {
        Path doc = Path.of("..", "docs", "reply-trace-instrumentation.md");
        assumeTrue(Files.exists(doc));
        assertThat(Files.readString(doc)).contains("`AI_TRACE=false`").contains("app.ai.trace.enabled");
    }

    @Test
    void aStoreBuiltOffCreatesNoTraceNoWriterAndNoMetricsAndRestoresTheDefaultWhenStopped() {
        var savedSink = ReplyTrace.sink;
        ReplyTraceStore store = new ReplyTraceStore(null, false);   // no database: any write attempt would be an error
        try {
            store.start();
            assertThat(ReplyTrace.enabled).isFalse();
            assertThat(ReplyTrace.begin("WEB")).isNull();   // no trace is created
            assertThat(ReplyTrace.currentId()).isNull();
            ReplyTrace.instant("note", "lines", 1);          // does nothing, never throws
            ReplyTrace.finish(false, null);
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            io.micrometer.core.instrument.Metrics.addRegistry(registry);
            try {
                long callsBefore = registry.find("elmanassa.provider.call").timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum();   // meters other tests registered earlier are not new
                ReplyMetrics.timer("elmanassa.test.off", 5, "channel", "WEB");
                ReplyMetrics.count("elmanassa.test.off.count", "channel", "WEB");
                ReplyTrace.callStart("glm", "m").end(true, null, null);
                assertThat(registry.find("elmanassa.test.off").meters()).isEmpty();      // nothing is recorded while it is off
                assertThat(registry.find("elmanassa.test.off.count").meters()).isEmpty();
                assertThat(registry.find("elmanassa.provider.call").timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum()).isEqualTo(callsBefore);
            } finally { io.micrometer.core.instrument.Metrics.removeRegistry(registry); }
            assertThat(store.written()).isZero();
            assertThat(store.failures()).isZero();
        } finally {
            store.stop();
            ReplyTrace.sink = savedSink;
        }
        assertThat(ReplyTrace.enabled).isTrue();   // stopping the context that turned it off puts the default back
    }
}

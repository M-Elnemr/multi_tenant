package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The trap probes (ids from 1000) are well formed and do not collide with the other evaluation ids. */
class AiEvalProbeSetTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void probesAreCompleteAndUnique() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (String f : new String[] {"/ai-eval.json", "/ai-eval-orders.json"})
            JSON.readTree(getClass().getResourceAsStream(f)).path("conversations").forEach(c -> ids.add(c.path("id").asInt()));
        int n = 0;
        for (JsonNode c : JSON.readTree(getClass().getResourceAsStream("/ai-eval-probes.json")).path("conversations")) {
            n++;
            assertThat(c.path("id").asInt()).isGreaterThanOrEqualTo(1000);
            assertThat(ids.add(c.path("id").asInt())).as("id " + c.path("id")).isTrue();
            assertThat(c.path("shop").asText()).isNotBlank();
            assertThat(c.path("customer")).isNotEmpty();
            assertThat(c.path("expect").size()).isGreaterThanOrEqualTo(2);
        }
        assertThat(n).isEqualTo(12);
    }
}

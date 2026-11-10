package com.platform.chat;

import java.util.List;

/**
 * Turns text into vectors for search by meaning («سماعة للجري» finds the sport neckband). Any provider with an OpenAI-compatible /embeddings endpoint works ({@link OpenAiEmbedder});
 * without a key it is off and the seller searches by words only, as before.
 */
public interface Embedder {
    /** Off when no key is configured: nothing is embedded and no search by meaning runs. */
    boolean enabled();

    /** True when the service is set up (a key exists), even while it rests after failures: the seller tells the model honestly that search is unavailable instead of hiding the tool. */
    default boolean configured() { return enabled(); }

    /** What an operator needs to see: calls, failures by kind, latency, resting. */
    default java.util.Map<String, Object> health() { return java.util.Map.of("enabled", enabled(), "configured", configured()); }

    /** The model's name, stored with every vector, so a change of model re-embeds everything. */
    String model();

    /** One unit-length vector per text, in order, for the index: patient (retries, a long timeout). Throws when the provider fails. */
    float[][] embed(List<String> texts);

    /**
     * One customer's words while they wait for a reply: one quick try that never takes more than a few seconds or more than the reply has left. Throws when the provider is slow
     * or fails (the caller searches by words).
     */
    default float[] embedQuery(String text) { return embed(List.of(text))[0]; }
}

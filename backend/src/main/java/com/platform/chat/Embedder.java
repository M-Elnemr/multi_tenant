package com.platform.chat;

import java.util.List;

/**
 * Turns text into vectors for search by meaning («سماعة للجري» finds the sport neckband). Any provider with an OpenAI-compatible /embeddings endpoint works ({@link OpenAiEmbedder});
 * without a key it is off and the seller searches by words only, as before.
 */
public interface Embedder {
    /** Off when no key is configured: nothing is embedded and no search by meaning runs. */
    boolean enabled();

    /** The model's name, stored with every vector, so a change of model re-embeds everything. */
    String model();

    /** One unit-length vector per text, in order. Throws when the provider fails (the caller falls back to words). */
    float[][] embed(List<String> texts);
}

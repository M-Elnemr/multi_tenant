package com.platform.chat;

import java.util.Map;

/** The self-hosted Evolution API (unofficial WhatsApp gateway). A thin seam so tests never need the real service. */
public interface EvolutionApi {
    /** Calls {@code method path} with an optional JSON body and returns the parsed JSON object (empty when the answer has no body), or throws {@link EvolutionException}. */
    Map<String, Object> call(String method, String path, Map<String, Object> body);

    class EvolutionException extends RuntimeException {
        private final int status;
        public EvolutionException(String message, int status) { super(message); this.status = status; }
        public int status() { return status; }
    }
}

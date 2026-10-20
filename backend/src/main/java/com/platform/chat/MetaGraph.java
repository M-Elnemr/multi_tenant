package com.platform.chat;

import java.util.Map;

/** Meta's Graph API. A thin seam so tests never touch the network. */
public interface MetaGraph {
    /** GET {path}; returns the parsed JSON object, or throws {@link MetaException}. */
    Map<String, Object> get(String path, String token);

    /** POST {path} with a JSON body. */
    Map<String, Object> post(String path, String token, Map<String, Object> body);

    class MetaException extends RuntimeException {
        public MetaException(String message) { super(message); }
    }
}

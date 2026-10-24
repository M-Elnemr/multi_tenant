package com.platform.chat;

import java.util.Map;

/** Meta's Graph API. A thin seam so tests never touch the network. */
public interface MetaGraph {
    /** GET {path} (token null for the login code exchange, which authenticates with the app secret in the query); returns the parsed JSON object, or throws {@link MetaException}. */
    Map<String, Object> get(String path, String token);

    /** POST {path} with a JSON body. */
    Map<String, Object> post(String path, String token, Map<String, Object> body);

    class MetaException extends RuntimeException {
        private final int code;
        public MetaException(String message) { this(message, 0); }
        public MetaException(String message, int code) { super(message); this.code = code; }
        /** Meta's own error code (100 = missing permission or object, 190 = bad token), 0 when unknown. */
        public int code() { return code; }
    }
}

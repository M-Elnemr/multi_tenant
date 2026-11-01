package com.platform.legal;

import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    private Json() {}

    static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}

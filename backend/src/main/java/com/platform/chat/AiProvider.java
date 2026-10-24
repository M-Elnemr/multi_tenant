package com.platform.chat;

import java.util.List;
import java.util.Map;

/** The language model behind the assistant. One implementation per vendor, chosen by {@code app.ai.provider}; tests plug in a scripted one. */
public interface AiProvider {
    record ToolDef(String name, String description, Map<String, Object> parameters) {}

    record ToolCall(String name, Map<String, Object> args) {}

    record ToolResult(String name, Map<String, Object> result) {}

    /**
     * One step of the exchange. {@code role} is "user", "model" or "tool". A model turn has text and/or tool calls; a tool turn carries the results.
     * {@code raw} is whatever the provider needs to replay its own turn exactly (opaque to everyone else).
     */
    /** Tokens the model billed for one call (cached = the part of the input served from the provider's prompt cache). */
    record Usage(int input, int output, int cached) {
        public static final Usage NONE = new Usage(0, 0, 0);
    }

    record Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results, Object raw, Usage usage) {
        public Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results, Object raw) { this(role, text, calls, results, raw, Usage.NONE); }
        public Turn withUsage(Usage u) { return new Turn(role, text, calls, results, raw, u); }
        public static Turn user(String text) { return new Turn("user", text, List.of(), List.of(), null); }
        public static Turn model(String text) { return new Turn("model", text, List.of(), List.of(), null); }
        public static Turn tool(List<ToolResult> results) { return new Turn("tool", null, List.of(), results, null); }
    }

    /** False while no API key is set: the storefront then hides the chat instead of showing a dead button. */
    default boolean available() { return true; }

    /** Next model turn for the conversation so far. Throws {@link AiUnavailableException} when the model cannot be reached or is not configured. */
    Turn next(String system, List<Turn> turns, List<ToolDef> tools);

    class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) { super(message); }
        public AiUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}

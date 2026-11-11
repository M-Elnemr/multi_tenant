package com.platform.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek sometimes writes its tool call as text («&lt;｜DSML｜tool_calls&gt;&lt;｜DSML｜invoke name="…"&gt;&lt;｜DSML｜parameter name="…" string="true"&gt;…») inside the reply instead of in the structured
 * {@code tool_calls}. Left alone, the customer reads that markup. This reads such calls so they run like normal ones, and removes every trace of the markup from the text.
 */
final class DsmlToolCalls {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BAR = "[｜|]\\s*";
    private static final Pattern INVOKE = Pattern.compile("<\\s*" + BAR + "DSML\\s*" + BAR + "invoke\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>(.*?)<\\s*/\\s*" + BAR + "DSML\\s*" + BAR + "invoke\\s*>", Pattern.DOTALL);
    private static final Pattern PARAM = Pattern.compile("<\\s*" + BAR + "DSML\\s*" + BAR + "parameter\\s+name\\s*=\\s*\"([^\"]+)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)<\\s*/\\s*" + BAR + "DSML\\s*" + BAR + "parameter\\s*>", Pattern.DOTALL);
    /** Any tag of the markup, complete or cut, and what follows an unclosed block. */
    private static final Pattern RESIDUE = Pattern.compile("<\\s*/?\\s*" + BAR + "DSML[^>]*>?", Pattern.DOTALL);

    private DsmlToolCalls() {}

    static boolean has(String text) { return text != null && RESIDUE.matcher(text).find(); }

    static List<AiProvider.ToolCall> read(String text) {
        List<AiProvider.ToolCall> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = INVOKE.matcher(text);
        while (m.find()) {
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher p = PARAM.matcher(m.group(2));
            while (p.find()) {
                String v = p.group(3).strip();
                Object value = v;
                if ("false".equals(p.group(2))) {   // not a string: a number, boolean, list or object, written as JSON
                    try { value = JSON.readValue(v, new TypeReference<Object>() {}); } catch (Exception ignored) { /* kept as text */ }
                }
                args.put(p.group(1), value);
            }
            out.add(new AiProvider.ToolCall(m.group(1), args));
        }
        return out;
    }

    /** The text without any of the markup (and without the half of a call that was cut off). */
    static String strip(String text) {
        if (text == null) return null;
        Matcher first = RESIDUE.matcher(text);
        if (!first.find()) return text;
        return text.substring(0, first.start()).strip();
    }
}

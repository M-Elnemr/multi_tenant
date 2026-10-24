package com.platform.marketing;

import java.util.Map;

/** Reads the JSON object a model answered with (the same tolerant reader the marketing writer uses). */
public final class AiTextHelper {
    private AiTextHelper() {}

    public static Map<String, Object> json(String text) { return AiContentService.json(text); }
}

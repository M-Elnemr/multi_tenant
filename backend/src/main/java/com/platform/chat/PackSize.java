package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much of the product the customer gets: its weight, volume, count or size («٣٠٠ جرام», «نصف توله», «١ لتر», «٦ قطع», «١٨٠ × ٧٠ سم»). The shop writes it in the product's name,
 * in its options, in its specs, in its description, or in several of them; the customer must hear it with the price and see it in the order summary, wherever it was written.
 * <p>
 * {@link #extra} gives it only when the name and the options do not already say it (they are shown anyway), so it is never repeated.
 */
public final class PackSize {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PackSize() {}

    private static final String NUM = "(?:[0-9٠-٩۰-۹]+(?:[.,٫][0-9٠-٩۰-۹]+)?|[½¼¾⅓⅔])";
    private static final String FRACTION = "(?:نصف|نص|ربع|ثلث|تلت|ثلاث\\s+ارباع|تلات\\s+ارباع|half|quarter)";
    private static final String UNIT = "(?:جرامات|جرام|جم|غرامات|غرام|gm|grams?|gr|g|كيلوجرام|كيلو\\s*جرام|كيلوهات|كيلو|كجم|كغ|kg|kilos?|مللي|ملي|ملل|مل|ml|لترات|لتر|liters?|litres?|ltr|l|"
            + "تولات|توله|تولة|أوقيات|اوقيات|أوقية|اوقيه|اوقية|أونصة|اونصه|اونصة|oz|ounces?|قراريط|قيراط|سم|cm|ملم|mm|مم|متر|meters?|m|بوصة|بوصه|inch(?:es)?|"
            + "قطع|قطعة|قطعه|حبات|حبة|حبه|pcs|pieces?|اكياس|أكياس|كيس|عبوات|عبوة|عبوه|علب|علبة|علبه|أزواج|ازواج|زوج|pairs?|رول|رولات|سرنجة|كبسولة|كبسولات|قرص|أقراص|اقراص)";
    /** «٣٠٠ جرام», «1.5 لتر», «١٨٠ × ٧٠ سم», «نصف توله», «نص كيلو», «ربع كيلو». */
    static final Pattern SIZE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:(?:" + NUM + "\\s*[x×*]\\s*)*" + NUM + "\\s*" + UNIT + "|" + FRACTION + "\\s+" + UNIT + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE);
    /** Spec names that hold the amount of the product (not the clothing size «المقاس: 42», which has no unit and so never matches anyway). */
    private static final Pattern SIZE_KEY = Pattern.compile("(?:وزن|الوزن|حجم|الحجم|سعه|سعة|السعه|السعة|محتوى|المحتوى|عبوه|عبوة|العبوه|العبوة|كميه|كمية|الكميه|الكمية|عدد|المقاس|مقاس|"
            + "weight|size|volume|capacity|content|net|pack|quantity|count)", Pattern.CASE_INSENSITIVE);

    /** The first size written in this text, as written, or null. */
    public static String in(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = SIZE.matcher(text);
        return m.find() ? m.group().replaceAll("\\s+", " ").trim() : null;
    }

    /**
     * The product's size to add to its name, or null when there is none or when its name or its options already say it. The specs are read first (a spec named like weight or size
     * whose value has a unit, then any spec value with a unit), then the description.
     *
     * @param optionsText every option name and value of the product, joined (null when none)
     * @param specsJson   the product's specs as stored, a JSON array of {"k","v"} (null when none)
     */
    public static String extra(String name, String optionsText, Object specsJson, String description) {
        if (in(name) != null || in(optionsText) != null) return null;
        String fromSpecs = fromSpecs(specsJson);
        return fromSpecs != null ? fromSpecs : in(description);
    }

    private static String fromSpecs(Object specsJson) {
        if (specsJson == null) return null;
        try {
            JsonNode arr = specsJson instanceof JsonNode n ? n : JSON.readTree(specsJson.toString());
            String any = null;
            for (JsonNode s : arr) {
                String k = s.path("k").asText(""), v = s.path("v").asText("");
                String found = in(v);
                if (found == null) continue;
                if (SIZE_KEY.matcher(k).find()) return found;
                if (any == null) any = found;
            }
            return any;
        } catch (Exception e) {
            return null;
        }
    }
}

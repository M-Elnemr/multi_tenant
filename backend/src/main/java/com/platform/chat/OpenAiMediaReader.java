package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one customer photo or voice note with a model behind an OpenAI-compatible chat endpoint that takes {@code image_url} and {@code input_audio} parts (DeepSeek V4.1 Flash, served by Hive). Unlike {@link QwenMediaReader} it never falls back to the caption: it answers {@code null} when it cannot read the media (HTTP error, empty
 * answer, or a model that says it cannot see or hear), so {@link FallbackMediaReader} can hand the same media to the next reader.
 */
final class OpenAiMediaReader {
    private static final Logger log = LoggerFactory.getLogger(OpenAiMediaReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String AUDIO_PROMPT = "Write down exactly the words said in this voice note, in the language and dialect spoken (Egyptian Arabic stays Egyptian Arabic). Only the words, nothing else. If you cannot hear any speech, answer exactly: UNREADABLE";
    /** A model that cannot take the media answers with an excuse instead of an error: that is "not read", not the customer's words. */
    private static final Pattern EXCUSE = Pattern.compile("(?is)^\\W*(unreadable|i('| a)?m sorry|sorry|i (can ?not|can't|am unable|'m unable|do not|don't)|unable to|as an ai|no (image|audio|photo)|لا أستطيع|لا استطيع|عذرا|عذراً|آسف|مش قادر)");

    private final AiUsageService usage;
    private final String code, baseUrl, model;
    private final java.util.function.Supplier<String> keys;
    private final boolean on, audio;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    OpenAiMediaReader(AiUsageService usage, String code, String key, String baseUrl, String model, boolean on, boolean audio) {
        this(usage, code, () -> key, baseUrl, model, on, audio);
    }

    /** The key is asked for at each request: the Hive pool lends the next one, so photos and voice notes share the keys' rate limits fairly. */
    OpenAiMediaReader(AiUsageService usage, String code, java.util.function.Supplier<String> keys, String baseUrl, String model, boolean on, boolean audio) {
        this.usage = usage;
        this.code = code;
        this.keys = keys;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model == null ? "" : model.trim();
        this.on = on;
        this.audio = audio;
    }

    private String key() { String k = keys.get(); return k == null ? "" : k.trim(); }

    boolean enabled() { return on && !key().isBlank() && !baseUrl.isBlank() && !model.isBlank(); }

    String code() { return code; }

    /** The chat text for the media («[صورة من العميل: …]» or «🎤 …», plus the caption), or null when this model could not read it. */
    String readText(UUID tenantId, String channel, MediaReader.Media media) {
        if (!enabled() || (!audio && media.kind() == MediaReader.Kind.AUDIO)) return null;
        String caption = media.caption() == null || media.caption().isBlank() ? null : media.caption().trim();
        try {
            byte[] bytes = media.bytes().get();
            if (bytes == null || bytes.length == 0) return null;
            String mime = QwenMediaReader.mime(media);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 300);
            if (media.kind() == MediaReader.Kind.IMAGE) {
                if (bytes.length > QwenMediaReader.MAX_IMAGE) return null;
                String text = QwenMediaReader.PHOTO_PROMPT + (caption == null ? "" : "\nThe customer's caption: «" + caption + "»");
                body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes))), Map.of("type", "text", "text", text)))));
                String said = call(tenantId, channel, body, "image", 30, "IMAGE", null);
                return said == null ? null : MediaReader.PHOTO + QwenMediaReader.clean(said) + "]" + (caption == null ? "" : "\n" + caption);
            }
            if (!audio || bytes.length > QwenMediaReader.MAX_AUDIO) return null;   // a model that takes no audio never gets a voice note
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "input_audio", "input_audio", Map.of("data", Base64.getEncoder().encodeToString(bytes), "format", audioFormat(mime))), Map.of("type", "text", "text", AUDIO_PROMPT)))));
            Integer secs = QwenMediaReader.oggSeconds(bytes);
            if (secs == null) secs = Math.max(1, bytes.length / 3000);
            String said = call(tenantId, channel, body, "voice " + secs + "s", 40, "AUDIO", secs);
            return said == null ? null : MediaReader.VOICE + said.trim() + (caption == null ? "" : "\n" + caption);
        } catch (RuntimeException e) {
            log.warn("{} could not read a customer's {}: {}", code, media.kind(), e.toString());
            return null;
        }
    }

    /** One request; the reply's text, or null when the call failed or the model made an excuse. */
    private String call(UUID tenantId, String channel, Map<String, Object> body, String what, int timeoutS, String kind, Integer seconds) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofSeconds(timeoutS))
                    .header("Authorization", "Bearer " + key()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("{} reading a {} failed: HTTP {} {}", code, what, res.statusCode(), res.body() == null ? "" : res.body().substring(0, Math.min(300, res.body().length())));
                return null;
            }
            JsonNode root = JSON.readTree(res.body());
            JsonNode c = root.path("choices").path(0).path("message").path("content");
            String text = c.isTextual() ? c.asText() : c.isArray() ? c.findValuesAsText("text").stream().reduce("", (a, b) -> a + b) : "";
            JsonNode u = root.path("usage");
            usage.recordMedia(tenantId, channel, kind, seconds, what + ": " + text,
                    new AiProvider.Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), Math.max(u.path("prompt_tokens_details").path("cached_tokens").asInt(0), u.path("prompt_cache_hit_tokens").asInt(0)), 0), code);
            if (unreadable(text)) {
                log.info("{} could not make sense of a {}: {}", code, what, text.length() > 120 ? text.substring(0, 120) : text);
                return null;
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("{} reading a {} failed: {}", code, what, e.toString());
            return null;
        }
    }

    /** Empty, or a short excuse («I can't see images», «UNREADABLE», «لا أستطيع…»): the model did not read the media. */
    static boolean unreadable(String text) {
        if (text == null || text.isBlank()) return true;
        String t = text.trim();
        return t.length() < 300 && EXCUSE.matcher(t).find();
    }

    /** OpenAI-style audio parts name the container: «audio/ogg» -> «ogg», «audio/mpeg» -> «mp3», «audio/mp4» / «audio/x-m4a» -> «mp4». */
    static String audioFormat(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return "mp4";
        if (m.contains("wav")) return "wav";
        if (m.contains("webm")) return "webm";
        if (m.contains("flac")) return "flac";
        return "ogg";
    }
}

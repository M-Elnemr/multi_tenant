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
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads photos with the Qwen chat model itself (Qwen 3.5+ sees images; thinking off it answers in about 3 s for about 2,500 tokens) and voice notes with Qwen's speech
 * recognition, on the same Alibaba key and workspace as the chat. Each read is logged as feature MEDIA, so its cost shows on the usage pages.
 */
/** Built by {@link FallbackMediaReader} (the one {@link MediaReader} bean), as its last resort. */
public class QwenMediaReader implements MediaReader {
    private static final Logger log = LoggerFactory.getLogger(QwenMediaReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_IMAGE = 5 * 1024 * 1024, MAX_AUDIO = 4 * 1024 * 1024;
    static final String PHOTO_PROMPT = """
            A customer of an online shop sent this photo in their chat with the shop. In 1-3 short lines, tell the shop's salesperson what it shows, in Egyptian Arabic \
            (in English if the customer's caption is English): the product (kind, colour, pattern, material, who it is for), and copy exactly any product name, price, size or text \
            written on it. A screenshot of a product page or a chat: the product name, price, colour and size it shows. A payment receipt or money transfer: say so, with the amount. \
            Anything else (a person, a place, a received item that is damaged or wrong): say what it is. Only what is visible; no greeting, no advice.
            اكتب الوصف بالعربي المصري (حتى لو الكلام اللي على الصورة إنجليزي)، إلا لو تعليق العميل بالإنجليزي.""";

    private final AiUsageService usage;
    private final String key, baseUrl, visionModel, asrModel;
    private final boolean on;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public QwenMediaReader(AiUsageService usage, String key, String baseUrl, boolean on, String visionModel, String asrModel) {
        this.usage = usage;
        this.key = key == null ? "" : key.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.on = on;
        this.visionModel = visionModel;
        this.asrModel = asrModel;
    }

    @Override public boolean enabled() { return on && !key.isBlank() && !baseUrl.isBlank(); }

    @Override
    public String read(UUID tenantId, String channel, Media media) {
        String caption = media.caption() == null || media.caption().isBlank() ? null : media.caption().trim();
        if (!enabled()) return caption;
        try {
            byte[] bytes = media.bytes().get();
            if (bytes == null || bytes.length == 0) return caption;
            String mime = mime(media);
            if (media.kind() == Kind.IMAGE) {
                if (bytes.length > MAX_IMAGE) return caption;
                String text = PHOTO_PROMPT + (caption == null ? "" : "\nThe customer's caption: «" + caption + "»");
                List<Object> content = List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes))), Map.of("type", "text", "text", text));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", visionModel);
                body.put("messages", List.of(Map.of("role", "user", "content", content)));
                body.put("max_tokens", 250);
                body.put("enable_thinking", false);
                String said = call(tenantId, channel, body, "image", 30, "IMAGE", null);
                return said == null ? caption : PHOTO + clean(said) + "]" + (caption == null ? "" : "\n" + caption);
            }
            if (bytes.length > MAX_AUDIO) return caption;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", asrModel);
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(Map.of("type", "input_audio", "input_audio", Map.of("data", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes)))))));
            body.put("stream", false);
            body.put("asr_options", Map.of("enable_itn", false));
            Integer secs = oggSeconds(bytes);
            if (secs == null) secs = Math.max(1, bytes.length / 3000);   // not Ogg (Messenger's mp4/aac): about 24 kbit/s, an estimate for the cost
            String said = call(tenantId, channel, body, "voice " + secs + "s", 40, "AUDIO", secs);
            return said == null ? caption : VOICE + said.trim() + (caption == null ? "" : "\n" + caption);
        } catch (RuntimeException e) {
            log.warn("Could not read a customer's {}: {}", media.kind(), e.toString());
            return caption;
        }
    }

    /** One request; the reply's text, or null. */
    private String call(UUID tenantId, String channel, Map<String, Object> body, String what, int timeoutS, String kind, Integer seconds) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofSeconds(timeoutS))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Reading a {} failed: HTTP {} {}", what, res.statusCode(), res.body() == null ? "" : res.body().substring(0, Math.min(300, res.body().length())));
                return null;
            }
            JsonNode root = JSON.readTree(res.body());
            JsonNode c = root.path("choices").path(0).path("message").path("content");
            String text = c.isTextual() ? c.asText() : c.isArray() ? c.findValuesAsText("text").stream().reduce("", (a, b) -> a + b) : "";
            JsonNode u = root.path("usage");
            if (seconds != null && u.path("seconds").asInt(0) > 0) seconds = u.path("seconds").asInt();   // the Singapore workspace says the billed seconds
            usage.recordMedia(tenantId, channel, kind, seconds, what + ": " + text,
                    new AiProvider.Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), u.path("prompt_tokens_details").path("cached_tokens").asInt(0), 0), "qwen");
            return text.isBlank() ? null : text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Reading a {} failed: {}", what, e.toString());
            return null;
        }
    }

    /**
     * The length of an Ogg Opus voice note (WhatsApp's) in seconds, from the last page's granule position (48 kHz), or null for another format. Speech recognition is billed by
     * the second and the US workspace reports no usage, so the length goes into the usage row's preview.
     */
    static Integer oggSeconds(byte[] b) {
        for (int i = b.length - 27; i >= 0; i--) {
            if (b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') {
                long g = 0;
                for (int k = 7; k >= 0; k--) g = (g << 8) | (b[i + 6 + k] & 0xFF);
                return g <= 0 ? null : (int) Math.max(1, Math.round(g / 48000.0));
            }
        }
        return null;
    }

    /** One line, without the Chinese or Japanese characters Qwen now and then slips into Arabic («وش领军»). */
    static String clean(String said) {
        return said.replaceAll("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+", "").replaceAll("\\s*\\n+\\s*", " ").replaceAll(" {2,}", " ").trim();
    }

    /** «audio/ogg; codecs=opus» -> «audio/ogg»; a missing type is guessed from the kind. */
    static String mime(Media m) {
        String t = m.mime() == null ? "" : m.mime().split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty() || !t.contains("/")) return m.kind() == Kind.IMAGE ? "image/jpeg" : "audio/ogg";
        return t;
    }

    @Override
    public byte[] download(String url, String bearerToken, int maxBytes) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET();
            if (bearerToken != null) b.header("Authorization", "Bearer " + bearerToken);
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
            if (res.body().length > maxBytes) throw new IllegalStateException("too large: " + res.body().length + " bytes");
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.toString());
        }
    }
}

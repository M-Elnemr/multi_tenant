package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The platform admin's AI settings: the model for text, for photos and for voice notes, how the prompt is written (lean or full) and DeepSeek's reasoning. Each setting is stored only
 * when the admin chose something other than the default; the defaults live here (and in the properties {@code app.ai.default.*}) and are shown with a "Default" tag. The keys the requests
 * are spread over are kept by {@link HiveKeyPool}.
 */
@Service
public class AiConfigService {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a setting may be: its code and the label the admin sees. */
    public record Option(String code, String label, String note) {}

    static final List<Option> TEXT = List.of(new Option("deepseek", "DeepSeek V4.1 Flash", null), new Option("glm", "GLM 5.3 Flash", null), new Option("qwen", "Alibaba Qwen 3.7 Flash", null),
            new Option("anthropic", "Claude Haiku", null));
    static final List<Option> IMAGE = List.of(new Option("deepseek", "DeepSeek V4.1 Flash", null), new Option("qwen", "Alibaba Qwen 3.7 Flash", null));
    /** Voice notes: only Qwen's speech model takes audio (DeepSeek on Hive answers HTTP 500 for ogg, wav and mp3: tested 2026-11-10). */
    static final List<Option> AUDIO = List.of(new Option("qwen", "Alibaba Qwen ASR", "Only this model takes voice notes"));
    static final List<Option> PROMPT = List.of(new Option("lean", "Lean", "Shorter prompt: fewer tokens per reply"), new Option("full", "Full", "The complete rule book and sales playbook"));
    static final List<Option> EFFORT = List.of(new Option("none", "Off", null), new Option("low", "Low", null), new Option("medium", "Medium", null), new Option("high", "High", null));

    /** The settings in force (each already resolved to the default when the admin chose none). */
    public record Config(String textModel, String imageModel, String audioModel, String promptMode, String deepseekEffort) {}

    private final JdbcClient jdbc;
    private final HiveKeyPool keys;
    private final Config defaults;
    private final String hiveUrl, hiveModel;
    private volatile Config cached;
    private volatile long cachedAt = 0, cachedVersion = -1;
    /** Bumped by every save: an instance that cached before it reads again (several services can exist in one JVM, e.g. in tests). */
    private static volatile long version = 0;
    private static final long TTL_MS = 10_000;

    /** The service the running application uses, for code that cannot be handed it (the prompt builder, a provider's reasoning setting). Null in a unit test. */
    private static volatile AiConfigService current;

    public AiConfigService(JdbcClient jdbc, HiveKeyPool keys, @Value("${app.ai.default.text-model:deepseek}") String text, @Value("${app.ai.default.image-model:deepseek}") String image,
                           @Value("${app.ai.default.audio-model:qwen}") String audio, @Value("${app.ai.default.prompt-mode:lean}") String prompt,
                           @Value("${app.ai.default.deepseek-effort:low}") String effort, @Value("${app.ai.glm.base-url:https://api-cdn.thehive.ai/api/v3}") String hiveUrl,
                           @Value("${app.ai.glm.model:zai-org/glm-5.3-flash}") String hiveModel) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.defaults = new Config(pick(TEXT, text, "deepseek"), pick(IMAGE, image, "deepseek"), pick(AUDIO, audio, "qwen"), pick(PROMPT, prompt, "lean"), pick(EFFORT, effort, "low"));
        this.hiveUrl = hiveUrl.trim().replaceAll("/+$", "");
        this.hiveModel = hiveModel;
        current = this;
    }

    private static String pick(List<Option> options, String v, String fallback) {
        String t = v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
        return options.stream().anyMatch(o -> o.code().equals(t)) ? t : fallback;
    }

    public Config defaults() { return defaults; }

    /** The settings in force now (read again at most every 10 seconds, at once after a save). */
    public Config get() {
        Config c = cached;
        if (c != null && cachedVersion == version && System.currentTimeMillis() - cachedAt < TTL_MS) return c;
        long v = version;
        Map<String, Object> r = row();
        if (r == null) return c != null ? c : defaults;   // the database could not be read: keep the last settings that were read (the defaults only when none was ever read), do not flip to the defaults for a moment
        c = resolve(r);
        cached = c;
        cachedVersion = v;
        cachedAt = System.currentTimeMillis();
        return c;
    }

    private Config resolve(Map<String, Object> r) {
        return new Config(pick(TEXT, str(r, "text_model"), defaults.textModel()), pick(IMAGE, str(r, "image_model"), defaults.imageModel()), pick(AUDIO, str(r, "audio_model"), defaults.audioModel()),
                pick(PROMPT, str(r, "prompt_mode"), defaults.promptMode()), pick(EFFORT, str(r, "deepseek_effort"), defaults.deepseekEffort()));
    }

    private static String str(Map<String, Object> r, String k) { Object v = r.get(k); return v == null ? null : v.toString(); }

    private Map<String, Object> row() {
        try { return jdbc.sql("SELECT text_model, image_model, audio_model, prompt_mode, deepseek_effort FROM chat.platform_ai_config WHERE id = 1").query().listOfRows().stream().findFirst().orElse(Map.of()); }
        catch (RuntimeException e) { return null; }   // no table or no database
    }

    /** Forget what every instance cached (a save does this; tests too). */
    static void forgetCached() { version++; }

    /** Lean prompt in force for a live reply (an evaluation run decides for itself). */
    static boolean leanPrompt() { AiConfigService c = current; return c != null && "lean".equals(c.get().promptMode()); }

    /** DeepSeek's reasoning setting in force, or null when no service is running (the provider then uses its configured one). */
    static String deepseekEffort() { AiConfigService c = current; return c == null ? null : c.get().deepseekEffort(); }

    // ---- the admin screen -------------------------------------------------------------------------------------------------------

    public Map<String, Object> view() {
        Map<String, Object> r = row();
        if (r == null) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI_CONFIG_UNAVAILABLE", "The AI settings cannot be read right now");
        Config now = resolve(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", section(TEXT, now.textModel(), defaults.textModel()));
        out.put("image", section(IMAGE, now.imageModel(), defaults.imageModel()));
        out.put("audio", section(AUDIO, now.audioModel(), defaults.audioModel()));
        out.put("promptMode", section(PROMPT, now.promptMode(), defaults.promptMode()));
        out.put("deepseekEffort", section(EFFORT, now.deepseekEffort(), defaults.deepseekEffort()));
        out.put("keys", keys.view());
        int n = keys.size();
        out.put("activeKeys", n);
        out.put("requestsPerSecond", n * 5);
        return out;
    }

    private static Map<String, Object> section(List<Option> options, String selected, String def) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Option o : options) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", o.code()); x.put("label", o.label()); x.put("note", o.note()); x.put("default", o.code().equals(def));
            list.add(x);
        }
        m.put("options", list); m.put("selected", selected); m.put("default", def); m.put("isDefault", selected.equals(def));
        return m;
    }

    /**
     * Saves the settings named in {@code body} (textModel, imageModel, audioModel, promptMode, deepseekEffort); a value of "default" (or null) goes back to the default, a value that is
     * not one of the options is refused.
     */
    public Map<String, Object> update(Map<String, Object> body, UUID by) {
        Map<String, Object> existing = row();
        if (existing == null) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI_CONFIG_UNAVAILABLE", "The AI settings cannot be read right now");
        Map<String, Object> cur = new LinkedHashMap<>(existing);
        Map<String, List<Option>> fields = Map.of("textModel", TEXT, "imageModel", IMAGE, "audioModel", AUDIO, "promptMode", PROMPT, "deepseekEffort", EFFORT);
        Map<String, String> columns = Map.of("textModel", "text_model", "imageModel", "image_model", "audioModel", "audio_model", "promptMode", "prompt_mode", "deepseekEffort", "deepseek_effort");
        for (var f : fields.entrySet()) {
            if (!body.containsKey(f.getKey())) continue;
            Object v = body.get(f.getKey());
            String t = v == null ? "default" : v.toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("default")) { cur.put(columns.get(f.getKey()), null); continue; }
            if (f.getValue().stream().noneMatch(o -> o.code().equals(t))) throw BusinessException.badRequest("VALIDATION_ERROR", "«" + t + "» is not an option for " + f.getKey());
            cur.put(columns.get(f.getKey()), t);
        }
        jdbc.sql("""
                INSERT INTO chat.platform_ai_config (id, text_model, image_model, audio_model, prompt_mode, deepseek_effort, updated_by, updated_at) VALUES (1, :t, :i, :a, :p, :e, :u, now())
                ON CONFLICT (id) DO UPDATE SET text_model = :t, image_model = :i, audio_model = :a, prompt_mode = :p, deepseek_effort = :e, updated_by = :u, updated_at = now()
                """).param("t", cur.get("text_model")).param("i", cur.get("image_model")).param("a", cur.get("audio_model")).param("p", cur.get("prompt_mode")).param("e", cur.get("deepseek_effort")).param("u", by).update();
        cachedAt = 0;
        version++;
        return view();
    }

    // ---- keys -------------------------------------------------------------------------------------------------------------------

    public Map<String, Object> addKey(String label, String key, UUID by) {
        String k = key == null ? "" : key.trim();
        if (k.length() < 8 || k.length() > 300 || k.matches(".*\\s.*")) throw BusinessException.badRequest("VALIDATION_ERROR", "That does not look like an API key");
        String l = label == null || label.isBlank() ? "Hive key" : label.trim();
        keys.add(l.length() > 60 ? l.substring(0, 60) : l, k, by);
        return view();
    }

    public Map<String, Object> setKeyEnabled(String id, boolean enabled) {
        if (!keys.setEnabled(id, enabled)) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Key not found, or it belongs to the server's environment");
        return view();
    }

    public Map<String, Object> deleteKey(String id) {
        if (!keys.delete(id)) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Key not found, or it belongs to the server's environment");
        return view();
    }

    /** One tiny request with this key (a few tokens, no customer involved): whether Hive accepts it and how long it takes. The key is never returned. */
    public Map<String, Object> testKey(String id) {
        HiveKeyPool.Lease l = keys.lease(id);
        if (l == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Key not found");
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String body = JSON.writeValueAsString(Map.of("model", hiveModel, "messages", List.of(Map.of("role", "user", "content", "ping")), "max_tokens", 5));
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(HttpRequest.newBuilder(URI.create(hiveUrl + "/chat/completions")).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + l.key()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            out.put("ok", res.statusCode() / 100 == 2); out.put("status", res.statusCode());
            if (res.statusCode() / 100 == 2) keys.ok(l); else keys.failed(l, res.statusCode());
            if (res.statusCode() / 100 != 2) { JsonNode e = JSON.readTree(res.body()); out.put("message", e.path("error").isTextual() ? e.path("error").asText() : e.path("error").path("message").asText("")); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("ok", false); out.put("status", 0);
        } catch (Exception e) {
            keys.failed(l, 0);
            out.put("ok", false); out.put("status", 0); out.put("message", "No answer from Hive");
        }
        out.put("ms", System.currentTimeMillis() - t0);
        return out;
    }
}

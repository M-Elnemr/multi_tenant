package com.platform.chat;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which language model answers customers on which channel, so two models can be compared on real traffic with the same prompt, tools and token rules.
 * Social channels (WhatsApp, Facebook, Instagram, and the shop's own WhatsApp number) follow {@code app.ai.route.social}, the website chat and the dashboard test chat follow
 * {@code app.ai.route.web}; "default" is the model chosen by {@code app.ai.provider}. Every other AI feature keeps using the default model.
 * The platform has GLM (the default, a bean in {@link AiConfig}), Claude Haiku and Qwen (both built here, not as beans, so the rest of the application still sees exactly one {@link AiProvider}).
 */
@Component
public class ChannelAi {
    private static final Logger log = LoggerFactory.getLogger(ChannelAi.class);
    private static final List<String> SOCIAL = List.of("WHATSAPP", "FACEBOOK", "INSTAGRAM");

    private final AiProvider defaultAi;
    private final AiProvider claude, glm, qwen;
    private final String social, web;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelAi(AiProvider defaultAi, @Value("${app.ai.route.social:default}") String social, @Value("${app.ai.route.web:default}") String web,
                     @Value("${app.ai.anthropic.api-key:}") String key, @Value("${app.ai.anthropic.model:claude-haiku-5-5}") String model, @Value("${app.ai.anthropic.effort:low}") String effort,
                     @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl, @Value("${app.ai.anthropic.cache-ttl:1h}") String cacheTtl,
                     @Value("${app.ai.route.chat-max-output-tokens:1000}") int chatMaxOutput,
                     @Value("${app.ai.glm.api-key:}") String glmKey, @Value("${app.ai.glm.model:zai-org/glm-5.3-flash}") String glmModel,
                     @Value("${app.ai.glm.base-url:https://api-cdn.thehive.ai/api/v3}") String glmUrl, @Value("${app.ai.glm.reasoning-effort:low}") String glmEffort,
                     @Value("${app.ai.qwen.api-key:}") String qwenKey, @Value("${app.ai.qwen.model:qwen3.7-flash}") String qwenModel,
                     @Value("${app.ai.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String qwenUrl, @Value("${app.ai.qwen.thinking:off}") String qwenThinking,
                     @Value("${app.ai.glm.temperature:}") String glmTemperature, @Value("${app.ai.qwen.temperature:}") String qwenTemperature,
                     @Value("${app.ai.glm.max-rps:5}") int glmRps, @Value("${app.ai.qwen.max-rps:0}") int qwenRps) {
        this(defaultAi, new AnthropicProvider(key, model, effort, baseUrl, cacheTtl, chatMaxOutput), new OpenAiProvider("glm", "GLM", glmKey, glmModel, glmEffort, glmUrl, chatMaxOutput, true, withTemperature("", glmTemperature), glmRps),
                new OpenAiProvider("qwen", "Alibaba Qwen", qwenKey, qwenModel, "", qwenUrl, chatMaxOutput, true, withTemperature(qwenExtra(qwenThinking), qwenTemperature), qwenRps), social, web);
    }

    /** Alibaba's compatible mode reads {@code enable_thinking}: "off" (default) and "on" set it, anything else ("unset") sends nothing, for a host that rejects the field. */
    static String qwenExtra(String thinking) {
        String t = thinking == null ? "" : thinking.trim().toLowerCase(Locale.ROOT);
        return "off".equals(t) ? "{\"enable_thinking\":false}" : "on".equals(t) ? "{\"enable_thinking\":true}" : "";
    }

    /** Adds {@code "temperature"} to a model's extra request settings when one is configured (empty = the service's own default). Lower means steadier answers that stick to the facts. */
    static String withTemperature(String extraJson, String temperature) {
        String t = temperature == null ? "" : temperature.trim();
        if (t.isEmpty()) return extraJson == null ? "" : extraJson;
        double v;
        try { v = Double.parseDouble(t); } catch (NumberFormatException e) { return extraJson == null ? "" : extraJson; }
        String field = "\"temperature\":" + v;
        if (extraJson == null || extraJson.isBlank()) return "{" + field + "}";
        String e = extraJson.trim();
        return e.substring(0, e.lastIndexOf('}')) + (e.replaceAll("\\s", "").equals("{}") ? "" : ",") + field + "}";
    }

    /** For tests: the default model and Claude. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, String social, String web) { this(defaultAi, claude, null, social, web); }

    /** The default model, Claude and GLM (picked on its own by a chat that typed "glm": no fallback, its errors are shown). */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider glm, String social, String web) { this(defaultAi, claude, glm, null, social, web); }

    /** The default model, Claude, GLM and Qwen (the last two picked on their own by a chat that typed their name, or by the route settings). */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider glm, AiProvider qwen, String social, String web) {
        this.defaultAi = defaultAi;
        this.claude = claude;
        this.glm = glm;
        this.qwen = qwen;
        this.social = social == null ? "default" : social.trim().toLowerCase(Locale.ROOT);
        this.web = web == null ? "default" : web.trim().toLowerCase(Locale.ROOT);
    }

    /** The model for one reply on {@code channel}: the routed one, falling back to the default if it fails before any tool exchange has begun. */
    public Routed forChannel(String channel) { return forChannel(channel, null); }

    /** Same, for a customer who picked a model with {@link #command}: {@code chosen} is "glm", "qwen" or "anthropic" (null = the channel's default). */
    public Routed forChannel(String channel, String chosen) {
        String route = chosen != null ? chosen : channel != null && SOCIAL.contains(channel) ? social : web;
        // a chat that typed "glm" is testing that model: whatever is wrong with it (no key, not active, rejected) is shown as its error instead of quietly answering with another one
        if ("glm".equals(chosen) && glm != null) return new Routed(glm, null, true, backupFor(glm));
        if ("qwen".equals(chosen) && qwen != null) return new Routed(qwen, null, true, backupFor(qwen));
        AiProvider primary = "qwen".equals(route) && qwen != null && qwen.available() ? qwen : "anthropic".equals(route) && claude.available() ? claude : "glm".equals(route) && glm != null && glm.available() ? glm : defaultAi;
        AiProvider backup = backupFor(primary);
        return new Routed(primary, backup, false, backup);
    }

    /**
     * The model that answers when {@code primary} fails: GLM and Qwen stand in for each other (two companies, so one outage never silences the shop), anything else falls back to the
     * default model. Null when there is no other working model.
     */
    AiProvider backupFor(AiProvider primary) {
        boolean primaryIsGlm = primary == defaultAi || primary == glm || "glm".equals(primary.code());
        if (primaryIsGlm) return qwen != null && qwen.available() ? qwen : null;
        return defaultAi.available() ? defaultAi : null;
    }

    /** "claude", "glm", "qwen" or "alibaba" (optionally after "use" / "switch to" / "model"): the model code the customer asked for, or null when the text is not such a command. */
    public static String command(String text) {
        if (text == null || text.length() > 40) return null;
        String n = FastAnswers.norm(text).replaceFirst("^(use|switch to|switch|change to|model|use model|talk to)\\s+", "");
        return switch (n) {
            case "claude", "claude haiku", "haiku" -> "anthropic";
            case "alibaba", "alibaba qwen", "alibaba cloud", "alibaba qwen flash", "qwen", "qwen flash", "qwen 3 7", "qwen3 7", "qwen 3 7 flash", "qwen3 7 flash", "qwen37", "qwen3 7flash" -> "qwen";
            case "glm", "glm flash", "glm 5", "glm 5 3", "glm 5 3 flash", "glm5", "glm53", "glm5 3 flash" -> "glm";
            default -> null;
        };
    }

    /** One reply's model. It stays on whichever model it fell back to for the rest of the reply, because a model's own tool-exchange blocks cannot be replayed to another. */
    public static final class Routed implements AiProvider {
        private final AiProvider fallback;
        private final AiProvider second;   // the other company's model, asked again when this one's reply still has a problem the code cannot put right
        private final boolean picked;
        private AiProvider active;
        private boolean fellBack;

        Routed(AiProvider primary, AiProvider fallback, boolean picked) { this(primary, fallback, picked, null); }

        Routed(AiProvider primary, AiProvider fallback, boolean picked, AiProvider second) { this.active = primary; this.fallback = fallback; this.picked = picked; this.second = second; }

        /** The same reply asked of the other model (a second opinion), or null when there is none or it is the one answering. */
        public Routed secondOpinion() {
            if (second == null || second == active || !second.available()) return null;
            return new Routed(second, null, false, null);
        }

        public boolean fellBack() { return fellBack; }

        /** True for a model the chat picked by name (a test of that model): its failure is shown as it is instead of a polite apology. */
        public boolean showsErrors() { return picked; }

        /**
         * For a reply whose model failed after its tool exchange had begun (which cannot be handed over mid-way): the same reply, from the start, on the fallback model. Null when there is
         * none or it is already the one that failed.
         */
        public Routed restartOnFallback() {
            if (fallback == null || active == fallback) return null;
            Routed r = new Routed(fallback, null, false);
            r.fellBack = true;
            return r;
        }

        @Override public String code() { return active.code(); }

        @Override public String label() { return active.label(); }

        @Override public boolean available() { return active.available() || (fallback != null && fallback.available()); }

        @Override
        public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
            boolean firstRound = turns.isEmpty() || "user".equals(turns.get(turns.size() - 1).role());
            if (fallback == null || active == fallback || !firstRound) return active.next(system, turns, tools);
            AiProvider routed = active;
            try {
                Turn t = routed.next(system, turns, tools);
                // a refusal or an empty answer from the routed model is not a reply a customer should get: ask the default model instead
                if (t.calls().isEmpty() && (t.text() == null || t.text().isBlank())) throw new AiUnavailableException("The routed model returned no answer");
                return t;
            } catch (AiUnavailableException e) {
                log.warn("{} did not answer, using {}: {}", routed.code(), fallback.code(), e.getMessage());
                active = fallback;
                fellBack = true;
                return fallback.next(system, turns, tools);
            }
        }
    }
}

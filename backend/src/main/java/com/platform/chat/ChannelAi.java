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
                     @Value("${app.ai.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String qwenUrl, @Value("${app.ai.qwen.thinking:off}") String qwenThinking) {
        this(defaultAi, new AnthropicProvider(key, model, effort, baseUrl, cacheTtl, chatMaxOutput), new OpenAiProvider("glm", "GLM", glmKey, glmModel, glmEffort, glmUrl, chatMaxOutput, true, ""),
                new OpenAiProvider("qwen", "Alibaba Qwen", qwenKey, qwenModel, "", qwenUrl, chatMaxOutput, true, qwenExtra(qwenThinking)), social, web);
    }

    /** Alibaba's compatible mode reads {@code enable_thinking}: "off" (default) and "on" set it, anything else ("unset") sends nothing, for a host that rejects the field. */
    static String qwenExtra(String thinking) {
        String t = thinking == null ? "" : thinking.trim().toLowerCase(Locale.ROOT);
        return "off".equals(t) ? "{\"enable_thinking\":false}" : "on".equals(t) ? "{\"enable_thinking\":true}" : "";
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
        if ("glm".equals(chosen) && glm != null) return new Routed(glm, null);
        if ("qwen".equals(chosen) && qwen != null) return new Routed(qwen, null);
        AiProvider primary = "qwen".equals(route) && qwen != null && qwen.available() ? qwen : "anthropic".equals(route) && claude.available() ? claude : "glm".equals(route) && glm != null && glm.available() ? glm : defaultAi;
        return new Routed(primary, defaultAi);
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
        private AiProvider active;
        private boolean fellBack;

        Routed(AiProvider primary, AiProvider fallback) { this.active = primary; this.fallback = fallback; }

        public boolean fellBack() { return fellBack; }

        /** True when there is no fallback, so a failure of this model must be shown to the customer as it is. */
        public boolean showsErrors() { return fallback == null; }

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

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
 * The routed model is the second one this class knows (Claude); it is built here, not as a bean, so the rest of the application still sees exactly one {@link AiProvider}.
 */
@Component
public class ChannelAi {
    private static final Logger log = LoggerFactory.getLogger(ChannelAi.class);
    private static final List<String> SOCIAL = List.of("WHATSAPP", "FACEBOOK", "INSTAGRAM");

    private final AiProvider defaultAi;
    private final AiProvider claude, gemini25, deepseek;
    private final String social, web;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelAi(AiProvider defaultAi, @Value("${app.ai.route.social:default}") String social, @Value("${app.ai.route.web:default}") String web,
                     @Value("${app.ai.anthropic.api-key:}") String key, @Value("${app.ai.anthropic.model:claude-haiku-5-5}") String model, @Value("${app.ai.anthropic.effort:low}") String effort,
                     @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl, @Value("${app.ai.anthropic.cache-ttl:1h}") String cacheTtl,
                     @Value("${app.ai.route.chat-max-output-tokens:1000}") int chatMaxOutput,
                     @Value("${app.ai.gemini.api-key:}") String geminiKey, @Value("${app.ai.gemini25.model:gemini-2.5-flash-lite}") String gemini25Model, @Value("${app.ai.gemini.hedge-ms:10000}") long hedgeMs,
                     @Value("${app.ai.deepseek.api-key:}") String deepseekKey, @Value("${app.ai.deepseek.model:deepseek-ai/deepseek-v4.1-flash-1017}") String deepseekModel,
                     @Value("${app.ai.deepseek.base-url:https://api-cdn.thehive.ai/api/v3}") String deepseekUrl, @Value("${app.ai.deepseek.reasoning-effort:none}") String deepseekEffort,
                     @Value("${app.ai.deepseek.extra-body:}") String deepseekExtra) {
        this(defaultAi, new AnthropicProvider(key, model, effort, baseUrl, cacheTtl, chatMaxOutput), new GeminiProvider(geminiKey, gemini25Model, hedgeMs, "gemini25"),
                new OpenAiProvider("deepseek", "DeepSeek", deepseekKey, deepseekModel, deepseekEffort, deepseekUrl, chatMaxOutput, true, deepseekExtra), social, web);
    }

    /** For tests: any two models. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, String social, String web) { this(defaultAi, claude, null, null, social, web); }

    /** For tests: three models. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider gemini25, String social, String web) { this(defaultAi, claude, gemini25, null, social, web); }

    /** All four models: the default (Gemini 3.1), Claude, Gemini 2.5 and DeepSeek. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider gemini25, AiProvider deepseek, String social, String web) {
        this.defaultAi = defaultAi;
        this.claude = claude;
        this.gemini25 = gemini25;
        this.deepseek = deepseek;
        this.social = social == null ? "default" : social.trim().toLowerCase(Locale.ROOT);
        this.web = web == null ? "default" : web.trim().toLowerCase(Locale.ROOT);
    }

    /** The model for one reply on {@code channel}: the routed one, falling back to the default if it fails before any tool exchange has begun. */
    public Routed forChannel(String channel) { return forChannel(channel, null); }

    /** Same, for a customer who picked a model with {@link #command}: {@code chosen} is "gemini", "gemini25" or "anthropic" (null = the channel's default). */
    public Routed forChannel(String channel, String chosen) {
        String route = chosen != null ? chosen : channel != null && SOCIAL.contains(channel) ? social : web;
        // a customer who typed "gemini 2.5" or "deepseek" is testing that model: whatever is wrong with it (no key, not active, rejected) is shown as its error instead of quietly answering with another one
        if ("gemini25".equals(chosen) && gemini25 != null) return new Routed(gemini25, null);
        if ("deepseek".equals(chosen) && deepseek != null) return new Routed(deepseek, null);
        AiProvider primary = "anthropic".equals(route) && claude.available() ? claude : "gemini25".equals(route) && gemini25 != null && gemini25.available() ? gemini25
                : "deepseek".equals(route) && deepseek != null && deepseek.available() ? deepseek : defaultAi;
        return new Routed(primary, defaultAi);
    }

    /** "claude", "gemini 3.1", "gemini 2.5" (optionally after "use" / "switch to" / "model"): the model code the customer asked for, or null when the text is not such a command. */
    public static String command(String text) {
        if (text == null || text.length() > 40) return null;
        String n = FastAnswers.norm(text).replaceFirst("^(use|switch to|switch|change to|model|use model|talk to)\\s+", "");
        return switch (n) {
            case "claude", "claude haiku", "haiku" -> "anthropic";
            case "gemini", "gemini 3 1", "gemini 31", "gemini 3", "gemini 3 1 flash lite" -> "gemini";
            case "gemini 2 5", "gemini 25", "gemini 2", "gemini 2 5 flash lite" -> "gemini25";
            case "deepseek", "deep seek", "deepseek flash", "deepseek v4", "deepseek v4 1", "deepseek v4 1 flash" -> "deepseek";
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

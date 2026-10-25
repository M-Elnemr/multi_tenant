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
    private final AiProvider claude;
    private final String social, web;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelAi(AiProvider defaultAi, @Value("${app.ai.route.social:default}") String social, @Value("${app.ai.route.web:default}") String web,
                     @Value("${app.ai.anthropic.api-key:}") String key, @Value("${app.ai.anthropic.model:claude-haiku-5-5}") String model, @Value("${app.ai.anthropic.effort:low}") String effort,
                     @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl, @Value("${app.ai.anthropic.cache-ttl:1h}") String cacheTtl,
                     @Value("${app.ai.route.chat-max-output-tokens:1000}") int chatMaxOutput) {
        this(defaultAi, new AnthropicProvider(key, model, effort, baseUrl, cacheTtl, chatMaxOutput), social, web);
    }

    /** For tests: any two models. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, String social, String web) {
        this.defaultAi = defaultAi;
        this.claude = claude;
        this.social = social == null ? "default" : social.trim().toLowerCase(Locale.ROOT);
        this.web = web == null ? "default" : web.trim().toLowerCase(Locale.ROOT);
    }

    /** The model for one reply on {@code channel}: the routed one, falling back to the default if it fails before any tool exchange has begun. */
    public Routed forChannel(String channel) {
        String route = channel != null && SOCIAL.contains(channel) ? social : web;
        AiProvider primary = "anthropic".equals(route) && claude.available() ? claude : defaultAi;
        return new Routed(primary, defaultAi);
    }

    /** One reply's model. It stays on whichever model it fell back to for the rest of the reply, because a model's own tool-exchange blocks cannot be replayed to another. */
    public static final class Routed implements AiProvider {
        private final AiProvider fallback;
        private AiProvider active;
        private boolean fellBack;

        Routed(AiProvider primary, AiProvider fallback) { this.active = primary; this.fallback = fallback; }

        public boolean fellBack() { return fellBack; }

        @Override public String code() { return active.code(); }

        @Override public String label() { return active.label(); }

        @Override public boolean available() { return active.available() || fallback.available(); }

        @Override
        public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
            boolean firstRound = turns.isEmpty() || "user".equals(turns.get(turns.size() - 1).role());
            if (active == fallback || !firstRound) return active.next(system, turns, tools);
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

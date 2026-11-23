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
    private static final List<String> SOCIAL = List.of("WHATSAPP", "FACEBOOK", "INSTAGRAM", "TELEGRAM", "TIKTOK", "X", "SNAPCHAT");

    private final AiProvider defaultAi;
    private final AiProvider claude, glm, qwen, deepseek;
    private final String social, web;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelAi(AiProvider defaultAi, @Value("${app.ai.route.social:default}") String social, @Value("${app.ai.route.web:default}") String web,
                     @Value("${app.ai.anthropic.api-key:}") String key, @Value("${app.ai.anthropic.model:claude-haiku-5-5}") String model, @Value("${app.ai.anthropic.effort:low}") String effort,
                     @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl, @Value("${app.ai.anthropic.cache-ttl:1h}") String cacheTtl,
                     @Value("${app.ai.route.chat-max-output-tokens:1000}") int chatMaxOutput,
                     @Value("${app.ai.glm.api-key:}") String glmKey, @Value("${app.ai.glm.model:zai-org/glm-5.3-flash}") String glmModel,
                     @Value("${app.ai.glm.base-url:https://api-cdn.thehive.ai/api/v3}") String glmUrl, @Value("${app.ai.glm.reasoning-effort:low}") String glmEffort,
                     @Value("${app.ai.qwen.api-key:}") String qwenKey, @Value("${app.ai.qwen.model:qwen3.7-flash}") String qwenModel,
                     @Value("${app.ai.qwen.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String qwenUrl, @Value("${app.ai.qwen.thinking:low}") String qwenThinking,
                     @Value("${app.ai.glm.temperature:}") String glmTemperature, @Value("${app.ai.qwen.temperature:}") String qwenTemperature,
                     @Value("${app.ai.glm.max-rps:5}") int glmRps, @Value("${app.ai.qwen.max-rps:0}") int qwenRps,
                     @Value("${app.ai.deepseek.api-key:}") String deepseekKey, @Value("${app.ai.deepseek.model:deepseek-ai/deepseek-v4.1-flash-1017}") String deepseekModel,
                     @Value("${app.ai.deepseek.base-url:https://api-cdn.thehive.ai/api/v3}") String deepseekUrl, @Value("${app.ai.deepseek.reasoning-effort:none}") String deepseekEffort,
                     @Value("${app.ai.deepseek.extra-body:}") String deepseekExtra, @Value("${app.ai.deepseek.max-rps:5}") int deepseekRps, HiveKeyPool hive) {
        this(defaultAi, new AnthropicProvider(key, model, effort, baseUrl, cacheTtl, chatMaxOutput), new OpenAiProvider("glm", "GLM", hive, glmModel, () -> glmEffort, glmUrl, chatMaxOutput, true, withTemperature("", glmTemperature), glmRps),
                new OpenAiProvider("qwen", "Alibaba Qwen", qwenKey, qwenModel, "", qwenUrl, chatMaxOutput, true, withTemperature(qwenExtra(qwenThinking), qwenTemperature), qwenRps),
                new OpenAiProvider("deepseek", "DeepSeek V4.1 Flash", hive, deepseekModel, () -> { String e = AiConfigService.deepseekEffort(); return e != null ? e : deepseekEffort; }, deepseekUrl, chatMaxOutput, true,
                        deepseekExtra == null ? "" : deepseekExtra, deepseekRps), social, web);
    }

    // ---- DR-R: provider health breaker and early cross-model failover ------------------------------------------------------------
    final ProviderHealth health = new ProviderHealth();
    /** The first model call of a reply that the primary has not answered after this long is sent to the backup model too (0 = off). */
    volatile long failoverMs = 8_000;
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "ai-failover-" + n.incrementAndGet()); t.setDaemon(true); return t; }
    });
    /** The share of a reply's time budget a call to a model that has a backup leaves for that backup. */
    static final long BACKUP_RESERVE_MS = 10_000;

    @Value("${app.ai.failover-ms:8000}")
    void failoverMs(long ms) { this.failoverMs = Math.max(0, ms); }

    @Value("${app.ai.breaker.enabled:true}")
    void breakerEnabled(boolean on) { health.enabled(on); }

    /** How long a model call may go unanswered, while a customer waits, before it is sent a second time (see OpenAiProvider.send). */
    @Value("${app.ai.hedge-ms:25000}")
    void hedgeMs(long ms) { OpenAiProvider.HEDGE_MS = ms; }

    /** Alibaba's compatible mode reads {@code enable_thinking}: "off" (default) and "on" set it, anything else ("unset") sends nothing, for a host that rejects the field. */
    static String qwenExtra(String thinking) {
        String t = thinking == null ? "" : thinking.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "off" -> "{\"enable_thinking\":false}";
            case "on" -> "{\"enable_thinking\":true}";
            case "low" -> "{\"enable_thinking\":true,\"thinking_budget\":1024}";   // Alibaba has no effort levels for Qwen3.7 Flash: a small budget is "low"
            case "medium" -> "{\"enable_thinking\":true,\"thinking_budget\":4096}";
            case "high" -> "{\"enable_thinking\":true,\"thinking_budget\":16384}";
            default -> "";
        };
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
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider glm, AiProvider qwen, String social, String web) { this(defaultAi, claude, glm, qwen, null, social, web); }

    /** All four: the default model, Claude, GLM, Qwen and DeepSeek. */
    public ChannelAi(AiProvider defaultAi, AiProvider claude, AiProvider glm, AiProvider qwen, AiProvider deepseek, String social, String web) {
        this.deepseek = deepseek;
        this.defaultAi = defaultAi instanceof FeatureAi f ? f.backup() : defaultAi;   // chat routing: the plain default (GLM), whatever the features use
        this.claude = claude;
        this.glm = glm;
        this.qwen = qwen;
        this.social = social == null ? "default" : social.trim().toLowerCase(Locale.ROOT);
        this.web = web == null ? "default" : web.trim().toLowerCase(Locale.ROOT);
    }

    private volatile AiConfigService config;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void config(AiConfigService config) { this.config = config; }

    /** The model for one reply on {@code channel}: the routed one, falling back to the default if it fails before any tool exchange has begun. */
    public Routed forChannel(String channel) { return forChannel(channel, null); }

    /** Same, for a customer who picked a model with {@link #command}: {@code chosen} is "glm", "qwen", "deepseek" or "anthropic" (null = the channel's default). */
    public Routed forChannel(String channel, String chosen) {
        AiConfigService cfg = config;
        // the platform admin's choice (AI configuration screen) decides which model answers; the route settings are only used where there is no such screen (unit tests)
        String route = chosen != null ? chosen : cfg != null ? cfg.get().textModel() : channel != null && SOCIAL.contains(channel) ? social : web;
        // a chat that typed "glm" is testing that model: whatever is wrong with it (no key, not active, rejected) is shown as its error instead of quietly answering with another one
        if ("glm".equals(chosen) && glm != null) return new Routed(glm, null, true, backupFor(glm)).owner(this);
        if ("qwen".equals(chosen) && qwen != null) return new Routed(qwen, null, true, backupFor(qwen)).owner(this);
        if ("deepseek".equals(chosen) && deepseek != null) return new Routed(deepseek, null, true, backupFor(deepseek)).owner(this);
        AiProvider primary = "qwen".equals(route) && qwen != null && qwen.available() ? qwen : "deepseek".equals(route) && deepseek != null && deepseek.available() ? deepseek
                : "anthropic".equals(route) && claude.available() ? claude : "glm".equals(route) && glm != null && glm.available() ? glm : defaultAi;
        AiProvider backup = backupFor(primary);
        return new Routed(primary, backup, false, backup).owner(this);
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

    /** "claude", "glm", "qwen", "deepseek" or "alibaba" (optionally after "use" / "switch to" / "model"): the model code the customer asked for, or null when the text is not such a command. */
    public static String command(String text) {
        if (text == null || text.length() > 40) return null;
        String n = FastAnswers.norm(text).replaceFirst("^(use|switch to|switch|change to|model|use model|talk to)\\s+", "");
        return switch (n) {
            case "claude", "claude haiku", "haiku" -> "anthropic";
            case "alibaba", "alibaba qwen", "alibaba cloud", "alibaba qwen flash", "qwen", "qwen flash", "qwen 3 7", "qwen3 7", "qwen 3 7 flash", "qwen3 7 flash", "qwen37", "qwen3 7flash" -> "qwen";
            case "deepseek", "deep seek", "deepseek v4", "deepseek v4 1", "deepseek v4 1 flash", "deepseek flash" -> "deepseek";
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
        private ChannelAi owner;   // DR-R: the health breaker and the failover settings (null: none, as in a test that builds a Routed by hand)
        private boolean bothTried;   // DR-R: the early failover already asked the backup too, so the caller must not ask it a second time

        Routed owner(ChannelAi o) { this.owner = o; return this; }

        Routed(AiProvider primary, AiProvider fallback, boolean picked) { this(primary, fallback, picked, null); }

        Routed(AiProvider primary, AiProvider fallback, boolean picked, AiProvider second) { this.active = primary; this.fallback = fallback; this.picked = picked; this.second = second; }

        /** The same reply asked of the other model (a second opinion), or null when there is none or it is the one answering. */
        public Routed secondOpinion() {
            if (second == null || second == active || !second.available()) return null;
            return new Routed(second, null, false, null).owner(owner);
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
            Routed r = new Routed(fallback, null, false).owner(owner);
            r.fellBack = true;
            return r;
        }

        @Override public String code() { return active.code(); }

        @Override public java.util.Map<String, Object> settings() { return active.settings(); }

        @Override public String label() { return active.label(); }

        @Override public boolean available() { return active.available() || (fallback != null && fallback.available()); }

        /**
         * One call on the model that is answering, with no fallback and no change of model, for a step that must never move the reply to another model: the review. {@link #next} treats a call
         * whose last turn is a user turn as the first round of a reply and, when the model fails, answers it with the fallback model and stays on it; the review is such a call, so a review
         * that timed out used to hand the whole reply (and the review itself) to GLM.
         */
        public Turn nextOnActive(String system, List<Turn> turns, List<ToolDef> tools) { return ReplyTrace.withRole(fellBack ? "fallback" : ReplyTrace.role(), () -> active.next(system, turns, tools)); }

        /** One call on {@code p}, timed and reported to the health breaker (a capped call, the review, is never held against the provider). {@code withBackup}: see {@link AiBudget#hasBackup}. */
        private Turn call(AiProvider p, boolean withBackup, String role, String system, List<Turn> turns, List<ToolDef> tools) {
            long t0 = System.nanoTime();
            if (withBackup) AiBudget.hasBackup(BACKUP_RESERVE_MS);
            try {
                Turn t = ReplyTrace.withRole(role, () -> p.next(system, turns, tools));
                if (owner != null && !AiBudget.callsCapped()) owner.health.success(p.code(), (System.nanoTime() - t0) / 1_000_000);
                return t;
            } catch (AiUnavailableException e) {
                if (owner != null && !AiBudget.callsCapped()) owner.health.failure(p.code(), e.category(), (System.nanoTime() - t0) / 1_000_000);
                throw e;
            } finally { if (withBackup) AiBudget.noBackup(); }
        }

        private static boolean usable(Turn t) { return t != null && (!t.calls().isEmpty() || (t.text() != null && !t.text().isBlank())); }

        @Override
        public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
            boolean firstRound = turns.isEmpty() || "user".equals(turns.get(turns.size() - 1).role());
            String role = fellBack ? "fallback" : ReplyTrace.role();   // measurement only: which model's calls these are in the reply's timeline
            if (fallback == null || active == fallback || !firstRound) return call(active, fallback != null && active != fallback, role, system, turns, tools);   // a later round (or no backup): stays on this model
            AiProvider routed = active;
            long routedStart = System.nanoTime();
            bothTried = false;
            try {
                Turn t;
                if (owner != null && !owner.health.allow(routed.code())) throw new AiUnavailableException("BREAKER_OPEN", "The health breaker of " + routed.code() + " is open", null);   // DR-R: a provider that keeps failing is not asked by every new message
                else if (owner != null && owner.failoverMs > 0 && AiBudget.remainingMs() != Long.MAX_VALUE && fallback.available()) t = raced(routed, role, system, turns, tools);
                else t = call(routed, true, role, system, turns, tools);
                // a refusal or an empty answer from the routed model is not a reply a customer should get: ask the default model instead
                if (active == routed && t.calls().isEmpty() && (t.text() == null || t.text().isBlank())) throw new AiUnavailableException("The routed model returned no answer");
                return t;
            } catch (AiUnavailableException e) {
                if (bothTried) throw e;   // DR-R: the backup was asked in parallel and failed too
                log.warn("{} did not answer, using {}: {} (cause: {})", routed.code(), fallback.code(), e.describe(), e.getCause() == null ? "none" : e.getCause().toString());   // the cause tells a stall (request timed out) from a refusal
                long fallbackStart = System.currentTimeMillis();
                AiTelemetry.step("event", "provider-fallback:" + routed.code() + "->" + fallback.code() + ":" + e.category(), fallbackStart, fallbackStart, true);
                active = fallback;
                fellBack = true;
                ReplyTrace.fallbackStarted(routed.code(), fallback.code(), e.category(), (System.nanoTime() - routedStart) / 1_000_000);
                long fallbackStartNano = System.nanoTime();
                boolean fellOk = false;
                try {
                    Turn t = call(fallback, false, "fallback", system, turns, tools);
                    fellOk = true;
                    return t;
                } finally { ReplyTrace.fallbackCompleted(routed.code(), fallback.code(), fallbackStartNano, fellOk); }
            }
        }

        /**
         * DR-R: the first call of a reply, with the early failover. The routed model is asked on a worker thread; when it has not answered after {@code failoverMs} the SAME request also goes to the
         * backup model, and the first usable answer wins. The slower call is ignored (not cancelled: the provider bills it either way) and its tokens are recorded against the shop, like the losing
         * twin of a hedge. When the routed model answers (or fails) inside that time, this is exactly the old behaviour.
         */
        private Turn raced(AiProvider routed, String role, String system, List<Turn> turns, List<ToolDef> tools) {
            var budget = AiBudget.capture();
            var trace = ReplyTrace.capture();
            var tel = AiTelemetry.capture();
            java.util.UUID tenant = AiBudget.tenant();
            long raceStart = System.nanoTime();
            var primary = submit(budget, trace, tel, routed, true, role, system, turns, tools);
            try { return primary.get(Math.min(owner.failoverMs, Math.max(1_000, AiBudget.remainingMs() - 1_000)), java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException silent) { /* the routed model is still silent: ask the backup too */ }
            catch (java.util.concurrent.ExecutionException failed) { throw asUnavailable(failed.getCause()); }
            catch (InterruptedException i) { Thread.currentThread().interrupt(); throw new AiUnavailableException("The AI assistant was interrupted", i); }
            long waited = (System.nanoTime() - raceStart) / 1_000_000;
            log.warn("{} has not answered after {} ms, asking {} as well (the first usable answer wins)", routed.code(), waited, fallback.code());
            long now = System.currentTimeMillis();
            AiTelemetry.step("event", "provider-failover:" + routed.code() + "->" + fallback.code(), now, now, false);
            bothTried = true;
            var backup = submit(budget, trace, tel, fallback, false, "fallback", system, turns, tools);
            var winner = new java.util.concurrent.CompletableFuture<Turn>();
            var winnerOf = new java.util.concurrent.atomic.AtomicReference<AiProvider>();
            Object lock = new Object();
            int[] failed = {0};
            var last = new java.util.concurrent.atomic.AtomicReference<AiUnavailableException>();
            for (var pair : List.of(java.util.Map.entry(routed, primary), java.util.Map.entry(fallback, backup))) {
                AiProvider p = pair.getKey();
                pair.getValue().whenComplete((t, ex) -> {
                    if (ex == null && usable(t)) {
                        boolean won;
                        synchronized (lock) { won = !winner.isDone(); if (won) { winnerOf.set(p); winner.complete(t); } }
                        if (!won) bill(tenant, p, t);   // the slower answer: the provider billed it, so it is recorded against the shop
                        return;
                    }
                    if (ex == null) bill(tenant, p, t);   // an empty answer is billed too
                    synchronized (lock) {
                        last.set(ex != null ? asUnavailable(ex) : new AiUnavailableException(ProviderFailure.EMPTY, "The model returned no answer", null));
                        if (++failed[0] == 2 && !winner.isDone()) winner.completeExceptionally(last.get());
                    }
                });
            }
            Turn t;
            try { t = winner.get(Math.max(1_000, AiBudget.remainingMs() - 500), java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.ExecutionException e) { active = fallback; fellBack = true; ReplyTrace.fallbackStarted(routed.code(), fallback.code(), "FAILOVER", waited); throw asUnavailable(e.getCause()); }
            catch (java.util.concurrent.TimeoutException e) { active = fallback; fellBack = true; throw new AiUnavailableException(ProviderFailure.TIMEOUT, "Neither model answered in time", e); }
            catch (InterruptedException i) { Thread.currentThread().interrupt(); throw new AiUnavailableException("The AI assistant was interrupted", i); }
            ReplyTrace.fallbackStarted(routed.code(), fallback.code(), "FAILOVER", waited);
            if (winnerOf.get() == fallback) { active = fallback; fellBack = true; }
            ReplyTrace.fallbackCompleted(routed.code(), fallback.code(), raceStart, winnerOf.get() == fallback);
            return t;
        }

        private java.util.concurrent.CompletableFuture<Turn> submit(AiBudget.Snapshot budget, ReplyTrace.Context trace, String[] tel, AiProvider p, boolean withBackup, String role, String system, List<Turn> turns, List<ToolDef> tools) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                AiBudget.adopt(budget); ReplyTrace.adopt(trace); AiTelemetry.adopt(tel);
                try { return call(p, withBackup, role, system, turns, tools); }
                finally { AiBudget.clear(); ReplyTrace.release(); AiTelemetry.release(); }
            }, POOL);
        }

        /** The tokens of a call whose answer was not used, recorded against the shop it was made for (the same place the losing twin of a hedge goes). */
        private static void bill(java.util.UUID tenant, AiProvider p, Turn t) {
            try {
                var sink = OpenAiProvider.LOST_TWIN_SINK;
                if (sink != null && tenant != null && t != null && t.usage() != null && t.usage().input() + t.usage().output() > 0) sink.lost(tenant, p.code(), t.usage());
            } catch (RuntimeException e) { log.warn("Could not record the tokens of an unused {} answer: {}", p.code(), e.toString()); }
        }

        private static AiUnavailableException asUnavailable(Throwable t) {
            for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) if (x instanceof AiUnavailableException a) return a;
            return new AiUnavailableException(ProviderFailure.CONNECTION, "The AI assistant could not be reached", t);
        }
    }
}

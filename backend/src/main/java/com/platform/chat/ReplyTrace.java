package com.platform.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The timeline of ONE inbound customer message, from the first webhook line to the last outbound send. It extends {@link ReplyTimeline} (which keeps its one log line): every stage is an
 * event with an offset (ms since the message arrived, from {@code System.nanoTime()}, never from subtracting wall clocks) and, for a span, its duration.
 * <p>
 * Naming: a span event stands for the pair {@code <name>_started} at {@code t} and {@code <name>_completed} at {@code t + d} (e.g. {@code memory_load}); instants have no {@code d}.
 * <p>
 * Privacy by construction: a strict allowlist of event names and attribute names, each with its own value type or value set (see {@link #SPECS}); everything else is dropped or stored as «?».
 * No message, prompt, answer, tool argument, phone number, id, header or key can enter a trace through this class, because callers pass categories, never texts.
 * <p>
 * Safety: nothing here throws into a reply (every public method swallows its own errors), nothing blocks (the persistence is a bounded queue drained by another thread), and nothing here
 * changes what a reply does. A span that nests another (a review contains its model call) is reported as it is; the {@code summary} sums model time only from {@code provider_call}
 * events, which never nest, so durations are not double counted. Calls made by a hedge's twin are children of their call and flagged {@code parallel}.
 */
final class ReplyTrace {
    static final int MAX_EVENTS = 160;
    /**
     * The ONLY things a trace may store. An event name outside {@link #EVENTS} is dropped; an attribute whose name is not in {@link #SPECS} is dropped; a number is kept only for a name that
     * takes numbers, as an integer from 0 up to a cap for that name (at most 10^7, so a mobile number, which is at least 10^9 as a number, can never be one); a string is kept only when it is one of the
     * listed values or matches the narrow pattern of its name, otherwise it is stored as «?». Nothing here takes free text, and no attribute can carry an id of a shop, chat, customer, order or message.
     */
    static final Set<String> EVENTS = Set.of("webhook_received", "burst_wait", "queue_wait", "processing_started", "phase", "context_load", "memory_load", "rag", "prompt_build", "prompt_ready", "prompt_parts",
            "provider_request_started", "provider_response_completed", "provider_request_failed", "provider_request_timed_out", "provider_attempt_hedged", "provider_hedge_request", "provider_call",
            "fallback_started", "fallback_completed", "tool", "rule_checks", "rewrite", "review", "outbound_completed", "outbound_failed", "reply_completed", "reply_failed", "note");

    /** How one attribute may look: NUM, BOOL, or a string from a set or matching a pattern. */
    private record Spec(char kind, Set<String> values, Pattern pattern, long max) {
        static Spec num(long max) { return new Spec('N', null, null, max); }
        static Spec bool() { return new Spec('B', null, null, 0); }
        static Spec of(String... values) { return new Spec('S', Set.of(values), null, 0); }
        static Spec pat(String regex) { return new Spec('S', null, Pattern.compile(regex), 0); }
    }

    private static final Map<String, Spec> SPECS = new java.util.HashMap<>();
    static {
        for (String n : List.of("lines", "call", "attempt", "attempts", "round", "problems", "rewrites", "history_messages", "facts", "tools", "model_calls")) SPECS.put(n, Spec.num(10_000));   // counts
        for (String n : List.of("quiet_ms", "throttle_ms", "after_ms", "waited_ms", "deterministic_ms", "rewrite_ms")) SPECS.put(n, Spec.num(10_000_000));       // milliseconds (under 3 hours)
        for (String n : List.of("in", "out", "cached", "chars", "static_chars", "dynamic_chars", "history_chars", "tools_chars")) SPECS.put(n, Spec.num(10_000_000));
        SPECS.put("mode", Spec.of("classic", "optimized"));   // which request layout answered (the admin's AI optimization switch)                                                              // tokens and characters
        for (String n : List.of("direct", "retry", "hedged", "usable", "allowed", "parallel", "winner", "billed", "ok", "next_model_call", "accepted_original", "threw", "differs", "confirmed", "ran", "unavailable",
                "ai_unavailable")) SPECS.put(n, Spec.bool());
        SPECS.put("model", Spec.pat("[A-Za-z][A-Za-z0-9_.\\-]{0,39}"));   // a provider code or a configured model id, e.g. glm-5
        SPECS.put("provider", Spec.pat("[a-z][a-z0-9_]{0,11}"));
        SPECS.put("from", Spec.pat("[a-z][a-z0-9_]{0,11}"));
        SPECS.put("to", Spec.pat("[a-z][a-z0-9_]{0,11}"));
        SPECS.put("role", Spec.of("primary", "fallback", "second_opinion"));
        SPECS.put("purpose", Spec.of("answer", "rewrite", "review", "final"));
        SPECS.put("outcome", Spec.of("ok", "timeout", "failed"));
        SPECS.put("status", Spec.of("none", "1xx", "2xx", "3xx", "4xx", "5xx", "accepted", "failed"));
        SPECS.put("which", Spec.of("first", "twin"));
        SPECS.put("reason", Spec.of("rule_check", "claims", "money"));
        SPECS.put("verdict", Spec.of("approved", "corrected", "kept_original", "timeout", "error", "unknown", "skipped"));
        SPECS.put("detail", Spec.pat("[a-z_]{0,24}"));
        SPECS.put("channel", Spec.of("WEB", "WHATSAPP", "MESSENGER", "INSTAGRAM", "TEST", "other"));
        SPECS.put("via", Spec.of("cloud", "graph", "evolution"));
        SPECS.put("kind", Spec.of("text", "buttons", "card", "media"));
        SPECS.put("name", Spec.of("prep", "prompt", "products", "flow", "post"));
        SPECS.put("failure", Spec.pat("[A-Z_]{0,24}"));      // a ProviderFailure category: never a message
        SPECS.put("category", Spec.pat("[A-Z_]{0,24}"));
        SPECS.put("error", Spec.pat("[A-Za-z0-9]{0,40}|rejected"));   // an exception CLASS name or «rejected»: never its message
        SPECS.put("tool", Spec.pat("[a-z_]{1,32}"));
        SPECS.put("kinds", Spec.pat("[A-Z_:]{0,40}"));       // names of the rule checks that fired
    }
    private static final ThreadLocal<ReplyTrace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ReplyTrace> DEFERRED = new ThreadLocal<>();   // a finished reply whose outbound sends are still to come (messaging channels)
    private static final ThreadLocal<Inbound> INBOUND = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();      // primary | fallback | second_opinion
    private static final ThreadLocal<String> PURPOSE = new ThreadLocal<>();   // answer | rewrite | review | final

    /** Where finished traces go (set by {@link ReplyTraceStore}); null = nowhere (tests, or tracing off). Must never block. */
    static volatile Consumer<ReplyTrace> sink;
    static volatile boolean enabled = true;

    /** One span or instant of the timeline. */
    record Event(String name, long t, Long d, Map<String, Object> a) {}

    /** What the burst holder knows about the lines that became this reply (all from {@code System.nanoTime()}). */
    static final class Inbound {
        final long firstNano, lastAddNano, flushNano, submitNano;
        final int lines;
        volatile long startNano;
        Inbound(long firstNano, long lastAddNano, long flushNano, long submitNano, int lines) {
            this.firstNano = firstNano; this.lastAddNano = lastAddNano; this.flushNano = flushNano; this.submitNano = submitNano; this.lines = lines;
        }
    }

    final String id = UUID.randomUUID().toString();
    final String channel;
    final long originNano;
    final long originWallMs;
    final long processingStartNano = System.nanoTime();
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger callSeq = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean failed;
    private volatile String source = "REPLY";
    private volatile UUID tenantId, conversationId;
    private volatile Map<String, Object> summary = Map.of();
    private volatile long replyMs = -1;
    private volatile String model;

    private ReplyTrace(String channel, Inbound in) {
        this.channel = channel != null && SPECS.get("channel").values().contains(channel) ? channel : "other";
        long now = processingStartNano;
        this.originNano = in != null ? in.firstNano : now;
        this.originWallMs = System.currentTimeMillis() - (now - originNano) / 1_000_000;
        if (in != null) {
            long burst = (in.flushNano - in.firstNano) / 1_000_000, queue = Math.max(0, (in.startNano == 0 ? now : in.startNano) - in.submitNano) / 1_000_000;
            add(new Event("webhook_received", 0, null, attrs("lines", in.lines)));
            add(new Event("burst_wait", 0, burst, attrs("lines", in.lines, "quiet_ms", (in.flushNano - in.lastAddNano) / 1_000_000)));
            add(new Event("queue_wait", (in.submitNano - originNano) / 1_000_000, queue, attrs()));
            ReplyMetrics.timer("elmanassa.reply.burst_wait", burst, "channel", ReplyMetrics.channel(channel));
            ReplyMetrics.timer("elmanassa.reply.queue_wait", queue, "channel", ReplyMetrics.channel(channel));
        } else add(new Event("webhook_received", 0, null, attrs("direct", true)));
        add(new Event("processing_started", (now - originNano) / 1_000_000, null, attrs()));
    }

    // ---- life of a trace -------------------------------------------------------------------------------------------------------------

    /** A reply begins on this thread. Picks up what the burst holder recorded for it, if it came through one. */
    static ReplyTrace begin(String channel) {
        try {
            ReplyTrace stale = DEFERRED.get();   // a reply whose channel never completed it (should not happen): saved now instead of lost
            DEFERRED.remove(); ROLE.remove(); PURPOSE.remove();
            if (stale != null) stale.complete();
            if (!enabled) { CURRENT.remove(); return null; }
            ReplyTrace t = new ReplyTrace(channel, INBOUND.get());
            CURRENT.set(t);
            return t;
        } catch (RuntimeException e) { CURRENT.remove(); return null; }
    }

    static ReplyTrace current() { return CURRENT.get(); }

    /** The id of the reply running on this thread (as logged and stored), or null. */
    static String currentId() { ReplyTrace t = CURRENT.get(); return t == null ? null : t.id; }

    /** The same id as 32 hex characters, the shape of a Langfuse trace id: {@link AiTelemetry} uses it so its spans and this trace are one record. */
    static String currentHexId() { String id = currentId(); return id == null ? null : id.replace("-", ""); }

    /** Which shop and chat this reply is for (known only after the conversation is found). */
    static void identify(UUID tenant, UUID conversation, boolean evalRun, boolean testChannel) {
        ReplyTrace t = CURRENT.get();
        if (t == null) return;
        t.tenantId = tenant; t.conversationId = conversation;
        t.source = evalRun ? "EVAL" : testChannel ? "TEST" : "REPLY";
    }

    /** The reply is over (the model flow and saving; the outbound send of a messaging channel may still follow). */
    static void finish(boolean failed, String model) {
        ReplyTrace t = CURRENT.get();
        CURRENT.remove();
        if (t == null) return;
        try {
            t.replyMs = (System.nanoTime() - t.processingStartNano) / 1_000_000;
            t.failed = failed;
            t.model = model != null ? model : t.lastUsableProvider();
            t.add(new Event(failed ? "reply_failed" : "reply_completed", t.since(t.processingStartNano) - t.replyMs, t.replyMs, t.attrs("model_calls", t.countCalls(), "model", t.lastUsableProvider())));
            // a messaging channel still sends after the reply: it adds its outbound events, then completes the trace (its respond() does, and the burst holder's worker as a backstop); WEB and TEST are done now
            boolean messaging = "WHATSAPP".equals(t.channel) || "MESSENGER".equals(t.channel) || "INSTAGRAM".equals(t.channel);
            if (!failed && (INBOUND.get() != null || messaging)) DEFERRED.set(t);
            else t.complete();
        } catch (RuntimeException ignored) { /* a trace never fails a reply */ }
    }

    /** Called by the burst holder's worker when the channel is done with the reply (sent, or decided to send nothing): persists the trace. */
    static void completeDeferred() {
        ReplyTrace t = DEFERRED.get();
        DEFERRED.remove();
        if (t != null) t.complete();
    }

    private void complete() {
        try {
            if (closed) return;
            closed = true;
            summary = summarize();
            ReplyMetrics.reply(this);
            org.slf4j.LoggerFactory.getLogger(ReplyTrace.class).info("Reply trace: id={} source={} channel={} outcome={} {}", id, source, channel, failed ? "failed" : "completed", compact(summary));
            Consumer<ReplyTrace> s = sink;
            if (s != null && tenantId != null) s.accept(this);
        } catch (RuntimeException ignored) { /* see finish */ }
    }

    // ---- inbound (the burst holder) --------------------------------------------------------------------------------------------------

    /** The worker thread starts running a burst's reply: what the holder measured is attached to the trace {@link #begin} creates. */
    static void inboundStart(Inbound in) { in.startNano = System.nanoTime(); INBOUND.set(in); }

    /** The worker is done with the burst: complete a trace still waiting for its outbound events and forget the burst. */
    static void inboundEnd() {
        try { completeDeferred(); } finally { INBOUND.remove(); DEFERRED.remove(); }
    }

    // ---- generic events --------------------------------------------------------------------------------------------------------------

    private long since(long nano) { return (nano - originNano) / 1_000_000; }

    private void add(Event e) {
        if (closed || !EVENTS.contains(e.name())) return;
        synchronized (events) { if (events.size() < MAX_EVENTS) events.add(e); }
    }

    /** An instant. {@code kv} are alternating names and values. */
    static void instant(String name, Object... kv) {
        try { ReplyTrace t = CURRENT.get(); if (t != null) t.add(new Event(name, t.since(System.nanoTime()), null, t.attrs(kv))); } catch (RuntimeException ignored) { /* see finish */ }
    }

    /** A span that began at {@code startNano} and ends now. */
    static void span(String name, long startNano, Object... kv) {
        try { ReplyTrace t = CURRENT.get(); if (t != null) t.add(new Event(name, t.since(startNano), (System.nanoTime() - startNano) / 1_000_000, t.attrs(kv))); } catch (RuntimeException ignored) { /* see finish */ }
    }

    /** Adds a span to the trace that is waiting for its outbound events (or the running one). */
    private static ReplyTrace open() { ReplyTrace t = CURRENT.get(); return t != null ? t : DEFERRED.get(); }

    // ---- provider calls --------------------------------------------------------------------------------------------------------------

    static void role(String role) { if (role == null) ROLE.remove(); else ROLE.set(role); }

    static String role() { String r = ROLE.get(); return r == null ? "primary" : r; }

    static void purpose(String purpose) { if (purpose == null) PURPOSE.remove(); else PURPOSE.set(purpose); }

    static String purpose() { String p = PURPOSE.get(); return p == null ? "answer" : p; }

    /** Runs {@code body} with a purpose set (review, rewrite...), then puts the previous one back. */
    static <T> T withPurpose(String purpose, Supplier<T> body) {
        String before = PURPOSE.get();
        purpose(purpose);
        try { return body.get(); } finally { purpose(before); }
    }

    static <T> T withRole(String role, Supplier<T> body) {
        String before = ROLE.get();
        role(role);
        try { return body.get(); } finally { role(before); }
    }

    /** One model call as the reply sees it: every attempt, retry and hedge inside it is part of it. Never null. */
    static Call callStart(String provider, String model) {
        ReplyTrace t = CURRENT.get();
        return new Call(t, t == null ? 0 : t.callSeq.incrementAndGet(), provider, model, role(), purpose());
    }

    static final class Call {
        private final ReplyTrace tr;
        final int id;
        final String provider, model, role, purpose;
        final long start = System.nanoTime();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean hedged, ended;

        Call(ReplyTrace tr, int id, String provider, String model, String role, String purpose) {
            this.tr = tr; this.id = id; this.provider = provider; this.model = model; this.role = role; this.purpose = purpose;
        }

        /** One HTTP request attempt (the first, or a retry on another key). Emits {@code provider_request_started}. */
        Attempt attempt(boolean retry) {
            Attempt a = new Attempt(this, attempts.incrementAndGet(), retry);
            try {
                if (tr != null) tr.add(new Event("provider_request_started", tr.since(a.start), null, tr.attrs("call", id, "attempt", a.no, "provider", provider, "role", role, "purpose", purpose, "retry", retry)));
                ReplyMetrics.count("elmanassa.provider.attempts", "provider", ReplyMetrics.code(provider), "role", ReplyMetrics.code(role), "kind", retry ? "retry" : "first");
            } catch (RuntimeException ignored) { /* see finish */ }
            return a;
        }

        /** The call is over: usable answer or not. Emits the call's one non-nesting {@code provider_call} span. */
        void end(boolean usable, String category, AiProvider.Usage usage) {
            if (ended) return;
            ended = true;
            try {
                long ms = (System.nanoTime() - start) / 1_000_000;
                String outcome = usable ? "ok" : ProviderFailure.TIMEOUT.equals(category) ? "timeout" : "failed";
                if (tr != null) tr.add(new Event("provider_call", tr.since(start), ms, tr.attrs("call", id, "provider", provider, "model", model, "role", role, "purpose", purpose, "attempts", attempts.get(), "hedged", hedged,
                        "usable", usable, "outcome", outcome, "failure", usable ? "" : category, "in", usage == null ? 0 : usage.input(), "out", usage == null ? 0 : usage.output(), "cached", usage == null ? 0 : usage.cached())));
                ReplyMetrics.timer("elmanassa.provider.call", ms, "provider", ReplyMetrics.code(provider), "role", ReplyMetrics.code(role), "purpose", ReplyMetrics.code(purpose), "outcome", outcome);
                if (!usable) ReplyMetrics.count("elmanassa.provider.failures", "provider", ReplyMetrics.code(provider), "category", ReplyMetrics.code(category));
                if ("timeout".equals(outcome)) ReplyMetrics.count("elmanassa.provider.timeouts", "provider", ReplyMetrics.code(provider));
            } catch (RuntimeException ignored) { /* see finish */ }
        }

        /** The same request was sent a second time because the first had not answered in {@code afterMs}. */
        void hedgeLaunched(long afterMs) {
            hedged = true;
            try {
                if (tr != null) tr.add(new Event("provider_attempt_hedged", tr.since(System.nanoTime()), null, tr.attrs("call", id, "provider", provider, "after_ms", afterMs, "allowed", true)));
                ReplyMetrics.count("elmanassa.provider.hedges", "provider", ReplyMetrics.code(provider), "result", "launched");
            } catch (RuntimeException ignored) { /* see finish */ }
        }

        /** The hedge threshold passed but this reply had already used its one twin: the first request is waited for to the end of its time. */
        void hedgeRefused(long afterMs) {
            try {
                if (tr != null) tr.add(new Event("provider_attempt_hedged", tr.since(System.nanoTime()), null, tr.attrs("call", id, "provider", provider, "after_ms", afterMs, "allowed", false)));
                ReplyMetrics.count("elmanassa.provider.hedges", "provider", ReplyMetrics.code(provider), "result", "refused");
            } catch (RuntimeException ignored) { /* see finish */ }
        }

        /**
         * One of the two parallel requests of a hedge, reported on its own the moment it ends (on whatever thread it ends): which one it was ({@code first} or {@code twin}),
         * how long it ran from its own start, its status, and whether it is the answer the reply used. Marked {@code parallel}: it overlaps its sibling and its call.
         * The loser is not cancelled (the provider keeps generating and bills it), so a loser that ends after the reply was saved reaches only the metrics.
         */
        void hedgeRequest(String which, long startNano, int httpStatus, String category, boolean winner, AiProvider.Usage usage) {
            try {
                long ms = (System.nanoTime() - startNano) / 1_000_000;
                boolean ok = httpStatus / 100 == 2 && (category == null || category.isEmpty());
                if (tr != null) tr.add(new Event("provider_hedge_request", tr.since(startNano), ms, tr.attrs("call", id, "which", which, "parallel", true, "status", statusClass(httpStatus), "failure", category == null ? "" : category,
                        "winner", winner, "usable", ok, "in", usage == null ? 0 : usage.input(), "out", usage == null ? 0 : usage.output(), "billed", ok)));
                ReplyMetrics.timer("elmanassa.provider.attempt", ms, "provider", ReplyMetrics.code(provider), "kind", "hedge_" + which, "outcome", ok ? (winner ? "won" : "lost") : "failed");
                if (ok && !winner) ReplyMetrics.count("elmanassa.provider.hedge_losers", "provider", ReplyMetrics.code(provider));
            } catch (RuntimeException ignored) { /* see finish */ }
        }
    }

    static final class Attempt {
        private final Call call;
        final int no;
        final boolean retry;
        final long start = System.nanoTime();
        private volatile boolean done;
        private volatile long throttleMs;

        /** Time spent waiting for this service's calls-per-second limit before the request was sent. */
        void throttled(long ms) { throttleMs = ms; }

        Attempt(Call call, int no, boolean retry) { this.call = call; this.no = no; this.retry = retry; }

        Call call() { return call; }

        /** The attempt ended with the HTTP status {@code httpStatus} (0 = no answer at all) and, when it failed, a failure category. */
        void finish(int httpStatus, String category, boolean timedOut, boolean usable, AiProvider.Usage usage) {
            if (done) return;
            done = true;
            try {
                ReplyTrace tr = call.tr;
                long ms = (System.nanoTime() - start) / 1_000_000;
                String name = usable ? "provider_response_completed" : timedOut ? "provider_request_timed_out" : "provider_request_failed";
                if (tr != null) tr.add(new Event(name, tr.since(start), ms, tr.attrs("call", call.id, "attempt", no, "provider", call.provider, "role", call.role, "purpose", call.purpose, "retry", retry, "hedged", call.hedged, "throttle_ms", throttleMs,
                        "status", statusClass(httpStatus), "failure", usable ? "" : category == null ? "" : category, "usable", usable, "in", usage == null ? 0 : usage.input(), "out", usage == null ? 0 : usage.output())));
                ReplyMetrics.timer("elmanassa.provider.attempt", ms, "provider", ReplyMetrics.code(call.provider), "kind", retry ? "retry" : "first", "outcome", usable ? "ok" : timedOut ? "timeout" : "failed");
            } catch (RuntimeException ignored) { /* see finish */ }
        }
    }

    static String statusClass(int status) { return status == 0 ? "none" : status / 100 + "xx"; }

    // ---- fallback --------------------------------------------------------------------------------------------------------------------

    /** The routed model failed and the fallback model takes the same request: {@code waitedMs} is what the failed model cost before this point. */
    static void fallbackStarted(String from, String to, String category, long waitedMs) {
        instant("fallback_started", "from", from, "to", to, "category", category, "waited_ms", waitedMs);
        ReplyMetrics.count("elmanassa.reply.fallbacks", "from", ReplyMetrics.code(from), "to", ReplyMetrics.code(to), "category", ReplyMetrics.code(category));
    }

    static void fallbackCompleted(String from, String to, long startNano, boolean ok) {
        span("fallback_completed", startNano, "from", from, "to", to, "ok", ok);
        ReplyMetrics.timer("elmanassa.reply.fallback_duration", (System.nanoTime() - startNano) / 1_000_000, "from", ReplyMetrics.code(from), "to", ReplyMetrics.code(to), "outcome", ok ? "ok" : "failed");
    }

    // ---- tools, rule checks, rewrites, review ----------------------------------------------------------------------------------------

    static void tool(String name, long startNano, boolean ok, int round) {
        String n = name != null && name.matches("[a-z_]{1,32}") ? name : "other";
        span("tool", startNano, "tool", n, "ok", ok, "round", round, "next_model_call", true);
        ReplyMetrics.timer("elmanassa.tool.duration", (System.nanoTime() - startNano) / 1_000_000, "tool", n, "outcome", ok ? "ok" : "failed");
    }

    /** The kinds of problem the deterministic rule checks found in a draft (names only: STALL, VAGUE, ...). */
    private static final ThreadLocal<List<String>> PROBLEMS = new ThreadLocal<>();

    static void checkProblems(java.util.Collection<String> kinds) { try { PROBLEMS.set(new ArrayList<>(kinds)); } catch (RuntimeException ignored) { /* see finish */ } }

    /**
     * Runs the rule checks of a draft and records them: the whole span, the part that was deterministic code (the span minus the rewrite calls inside it, which include whatever
     * validation followed the rewrite) and the rewrite time. Rewrites are separate {@code rewrite} spans inside it.
     */
    static String ruleChecks(Supplier<String> body, String draftIn) {
        long start = System.nanoTime();
        int rewritesBefore = rewriteRounds();
        long rewriteMsBefore = rewriteMs();
        PROBLEMS.remove();
        String out = null;
        boolean threw = true;
        try { out = body.get(); threw = false; return out; }
        finally {
            try {
                long total = (System.nanoTime() - start) / 1_000_000, rw = rewriteMs() - rewriteMsBefore;
                int rounds = rewriteRounds() - rewritesBefore;
                List<String> kinds = PROBLEMS.get();
                PROBLEMS.remove();
                boolean changed = !threw && out != null && !out.equals(draftIn);
                String joined = kinds == null ? "" : String.join(":", kinds).replaceAll("[^A-Za-z0-9_:.\\-]", "");
                span("rule_checks", start, "problems", kinds == null ? 0 : kinds.size(), "kinds", joined.substring(0, Math.min(40, joined.length())),
                        "rewrites", rounds, "deterministic_ms", Math.max(0, total - rw), "rewrite_ms", rw, "accepted_original", !changed && rounds == 0, "threw", threw);
                ReplyMetrics.timer("elmanassa.rulechecks.duration", total, "outcome", rounds > 0 ? "rewritten" : (kinds == null || kinds.isEmpty()) ? "clean" : "fixed_by_code");
                ReplyMetrics.timer("elmanassa.rulechecks.deterministic", Math.max(0, total - rw));
            } catch (RuntimeException ignored) { /* see finish */ }
        }
    }

    /** A rewrite model loop (the model is asked to write the reply again because a check found a problem). */
    static String rewrite(String purpose, Supplier<String> body) {
        long start = System.nanoTime();
        boolean ok = false;
        try { String r = withPurpose("rewrite", body); ok = r != null && !r.isBlank(); return r; }
        finally {
            span("rewrite", start, "reason", purpose, "usable", ok);
            ReplyMetrics.timer("elmanassa.rewrite.duration", (System.nanoTime() - start) / 1_000_000, "reason", ReplyMetrics.code(purpose), "outcome", ok ? "ok" : "empty");
            ReplyMetrics.count("elmanassa.rewrite.rounds", "reason", ReplyMetrics.code(purpose));
        }
    }

    private static int rewriteRounds() { ReplyTrace t = CURRENT.get(); return t == null ? 0 : (int) t.snapshot().stream().filter(e -> "rewrite".equals(e.name())).count(); }

    private static long rewriteMs() { ReplyTrace t = CURRENT.get(); return t == null ? 0 : t.snapshot().stream().filter(e -> "rewrite".equals(e.name())).mapToLong(e -> e.d() == null ? 0 : e.d()).sum(); }

    /** The review's verdict. {@code verdict}: approved, corrected, kept_original, rejected, timeout, error, unknown, skipped. */
    static void review(String verdict, long startNano, String model, String detail, boolean differs, AiProvider.Usage usage) {
        span("review", startNano, "verdict", verdict, "model", model == null ? "none" : model, "detail", detail == null ? "" : detail, "differs", differs,
                "in", usage == null ? 0 : usage.input(), "out", usage == null ? 0 : usage.output());
        ReplyMetrics.timer("elmanassa.review.duration", (System.nanoTime() - startNano) / 1_000_000, "verdict", ReplyMetrics.code(verdict));
        ReplyMetrics.count("elmanassa.review.verdicts", "verdict", ReplyMetrics.code(verdict));
    }

    /** Only the flags the allowlist knows (today: {@code ai_unavailable}); any other key is ignored. */
    static void note(String key, boolean value) { if ("ai_unavailable".equals(key)) instant("note", key, value); }

    // ---- outbound --------------------------------------------------------------------------------------------------------------------

    /**
     * One outbound send to a channel, timed. {@code kind}: text, buttons, card, media. A send that returns means the platform ACCEPTED the request; none of the channels gives a
     * delivery confirmation back to this code, so the status is {@code accepted} or {@code failed}, never «delivered».
     */
    static <T> T outbound(String channel, String via, String kind, Supplier<T> send) { return outbound(channel, via, kind, send, r -> true); }

    /** Same, for a send that reports failure by its result instead of throwing ({@code accepted} says whether the platform took it). */
    static <T> T outbound(String channel, String via, String kind, Supplier<T> send, java.util.function.Predicate<T> accepted) {
        long start = System.nanoTime();
        boolean ok = false;
        String category = "";
        try { T r = send.get(); ok = accepted.test(r); if (!ok) category = "rejected"; return r; }
        catch (RuntimeException e) { category = e.getClass().getSimpleName(); throw e; }
        finally {
            try {
                ReplyTrace t = open();
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (t != null) t.add(new Event(ok ? "outbound_completed" : "outbound_failed", t.since(start), ms, t.attrs("channel", channel, "via", via, "kind", kind, "status", ok ? "accepted" : "failed", "confirmed", false, "error", category)));
                ReplyMetrics.timer("elmanassa.outbound.duration", ms, "channel", ReplyMetrics.channel(channel), "via", ReplyMetrics.code(via), "kind", ReplyMetrics.code(kind), "status", ok ? "accepted" : "failed");
                if (!ok) ReplyMetrics.count("elmanassa.outbound.failures", "channel", ReplyMetrics.channel(channel), "via", ReplyMetrics.code(via));
            } catch (RuntimeException ignored) { /* see finish */ }
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------------

    List<Event> snapshot() { synchronized (events) { return new ArrayList<>(events); } }

    String source() { return source; }

    UUID tenantId() { return tenantId; }

    UUID conversationId() { return conversationId; }

    boolean failed() { return failed; }

    long replyMs() { return replyMs; }

    String model() { return model; }

    Map<String, Object> summary() { return summary; }

    /** The model whose answer the reply used: the provider of the last usable model call that was not a review ("none" when no model answered). */
    String lastUsableProvider() {
        String m = "none";
        for (Event e : snapshot()) if ("provider_call".equals(e.name()) && Boolean.TRUE.equals(e.a().get("usable")) && !"review".equals(e.a().get("purpose"))) m = String.valueOf(e.a().get("provider"));
        return m.matches("[a-z0-9_]{1,12}") ? m : "other";
    }

    int countCalls() { return (int) snapshot().stream().filter(e -> "provider_call".equals(e.name())).count(); }

    /** Everything a trace says about where time went, without nested durations summed together. */
    private Map<String, Object> summarize() {
        List<Event> es = snapshot();
        Map<String, Object> s = new LinkedHashMap<>();
        long end = es.stream().mapToLong(e -> e.t() + (e.d() == null ? 0 : e.d())).max().orElse(0);
        s.put("reply_ms", replyMs);
        s.put("end_to_end_ms", end);
        long burst = 0, queue = 0;
        for (Event e : es) { if ("burst_wait".equals(e.name())) burst = e.d(); if ("queue_wait".equals(e.name())) queue = e.d(); }
        s.put("burst_ms", burst);
        s.put("queue_ms", queue);
        long calls = 0, modelMs = 0, failedMs = 0, in = 0, out = 0, hedges = 0, fallbacks = 0, toolMs = 0, tools = 0, outMs = 0, outFail = 0, checkMs = 0, detMs = 0, rewriteMs = 0, rewrites = 0, reviewMs = 0;
        String verdict = "none", mode = null;
        long cached = 0, secondOpinions = 0, secondSkipped = 0;
        Map<String, Object> parts = new LinkedHashMap<>();
        Map<String, Long> byPurpose = new TreeMap<>();
        for (Event e : es) {
            long d = e.d() == null ? 0 : e.d();
            switch (e.name()) {
                case "provider_call" -> {
                    calls++; modelMs += d;
                    if (!Boolean.TRUE.equals(e.a().get("usable"))) failedMs += d;
                    in += ((Number) e.a().getOrDefault("in", 0)).longValue(); out += ((Number) e.a().getOrDefault("out", 0)).longValue();
                    if (Boolean.TRUE.equals(e.a().get("usable")) && e.a().get("cached") instanceof Number c) cached += c.longValue();
                    if ("second_opinion".equals(e.a().get("role"))) secondOpinions++;
                    byPurpose.merge(String.valueOf(e.a().get("role")) + "/" + e.a().get("purpose"), d, Long::sum);
                }
                case "provider_attempt_hedged" -> { if (Boolean.TRUE.equals(e.a().get("allowed"))) hedges++; }
                case "fallback_started" -> fallbacks++;
                case "tool" -> { tools++; toolMs += d; }
                case "outbound_completed" -> outMs += d;
                case "outbound_failed" -> { outMs += d; outFail++; }
                case "rule_checks" -> { checkMs += d; detMs += ((Number) e.a().getOrDefault("deterministic_ms", 0)).longValue(); }
                case "rewrite" -> { rewrites++; rewriteMs += d; }
                case "review" -> { reviewMs += d; verdict = String.valueOf(e.a().get("verdict")); }
                case "prompt_parts" -> {
                    if (e.a().get("mode") != null) mode = String.valueOf(e.a().get("mode"));
                    for (String k : List.of("static_chars", "dynamic_chars", "history_chars", "tools_chars")) if (e.a().get(k) != null) parts.put(k, e.a().get(k));
                }
                case "note" -> { if (String.valueOf(e.a().get("kinds")).startsWith("SKIP_SECOND")) secondSkipped++; }
                default -> { }
            }
        }
        s.put("model_calls", calls); s.put("model_ms", modelMs); s.put("failed_model_ms", failedMs);
        s.put("tokens_in", in); s.put("tokens_out", out); s.put("tokens_cached", cached);
        if (mode != null) s.put("prompt_mode", mode);
        s.putAll(parts);
        s.put("second_opinion_calls", secondOpinions); s.put("second_opinions_skipped", secondSkipped);
        s.put("model_ms_by_role_purpose", byPurpose);
        s.put("hedges", hedges); s.put("fallbacks", fallbacks);
        s.put("tools", tools); s.put("tool_ms", toolMs);
        s.put("rule_checks_ms", checkMs); s.put("rule_checks_deterministic_ms", detMs);
        s.put("rewrites", rewrites); s.put("rewrite_ms", rewriteMs);
        s.put("review_ms", reviewMs); s.put("review_verdict", verdict);
        s.put("outbound_ms", outMs); s.put("outbound_failures", outFail);
        return s;
    }

    private static String compact(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (var e : m.entrySet()) if (!(e.getValue() instanceof Map)) b.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        return b.toString().trim();
    }

    /** At most 6 digits in all and no run of 5: a phone number (7 digits at the very least) or an id can never pass as a string value, even with separators or letters around it. */
    private static boolean fewDigits(String s) {
        int total = 0, run = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) { total++; if (++run >= 5) return false; } else run = 0;
        }
        return total <= 6;
    }

    /** Attribute map under the allowlist: unknown names are dropped, numbers must be small non-negative integers, strings must be an allowed value or match their name's pattern (else «?»). */
    private Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String name = String.valueOf(kv[i]);
            Spec spec = SPECS.get(name);
            Object v = kv[i + 1];
            if (spec == null || v == null) continue;
            switch (spec.kind()) {
                case 'N' -> { if ((v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) && ((Number) v).longValue() >= 0 && ((Number) v).longValue() <= spec.max()) m.put(name, v); }
                case 'B' -> { if (v instanceof Boolean) m.put(name, v); }
                default -> {
                    if (!(v instanceof String sv)) break;
                    boolean ok = spec.values() != null ? spec.values().contains(sv) : spec.pattern().matcher(sv).matches() && fewDigits(sv);
                    m.put(name, ok ? sv : "?");
                }
            }
        }
        return m;
    }

    long originWallMs() { return originWallMs; }
}

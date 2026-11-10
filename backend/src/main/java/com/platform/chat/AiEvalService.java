package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.chat.AiProvider.Turn;
import com.platform.shared.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scores the AI seller instead of guessing: plays every golden conversation of {@code ai-eval.json} against a real shop on the TEST channel (nothing is ordered or billed) with the chosen model,
 * then a judge model grades each conversation against its rubric. One run at a time per platform; results live in memory until the next restart.
 */
@Service
public class AiEvalService {
    private static final Logger log = LoggerFactory.getLogger(AiEvalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatService chat;
    private final ChannelAi channelAi;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ai-eval"); t.setDaemon(true); return t; });
    private final Map<UUID, Map<String, Object>> runs = new ConcurrentHashMap<>();

    public AiEvalService(ChatService chat, ChannelAi channelAi, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.chat = chat;
        this.channelAi = channelAi;
        this.jdbc = jdbc;
    }

    static final String JUDGE = """
            You grade an online shop's AI salesperson. You get one test conversation (the customer's messages and the salesperson's replies) and the rubric: what a great Egyptian salesperson does here.
            For each rubric line decide pass or fail, strictly, from the replies only. Also fail any line if a reply invents product facts, urgency or discounts tied to ordering now, uses the wrong gender,
            offers kids' products to an adult buying for themself, ignores a question, or uses odd or wrong words.
            Answer with JSON only: {"checks":[{"expect":"<rubric line>","pass":true|false,"why":"<few words>"}],"note":"<one sentence on what would make it better>"}
            """;

    /** Starts a run on {@code tenantId} with {@code model} ("default", "glm", "anthropic", "qwen"); returns its id. */
    public UUID start(UUID tenantId, String model, String only) { return start(tenantId, model, only, "MANUAL"); }

    /** True while a run is going (one at a time per platform). */
    public boolean busy() { return runs.values().stream().anyMatch(r -> "RUNNING".equals(r.get("status"))); }

    /** Same, as a {@code kind} of run (MANUAL, NIGHTLY, WEEKLY): when it ends, its score is kept and compared with the last run of the same kind and model. */
    public UUID start(UUID tenantId, String model, String only, String kind) {
        if (runs.values().stream().anyMatch(r -> "RUNNING".equals(r.get("status")))) throw BusinessException.conflict("EVAL_RUNNING", "An evaluation is already running, wait for it to finish");
        String m = model == null || model.isBlank() ? "default" : model;
        if (!ChatService.TRAIN_MODELS.contains(m)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown model");
        List<JsonNode> set = new ArrayList<>();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (only != null && !only.isBlank()) for (String s : only.split(",")) try { ids.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { /* skipped */ }
        // a conversation written for one demo shop ("shop": its address) is only played on that shop
        String slug = jdbc.sql("SELECT slug FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("");
        try {
            for (JsonNode c : JSON.readTree(getClass().getResourceAsStream("/ai-eval.json")).path("conversations"))
                if (c.hasNonNull("shop") && !c.path("shop").asText().equals(slug)) continue;
                else if (ids.isEmpty() || ids.contains(c.path("id").asInt()) || c.path("category").asText().equals(only)) set.add(c);
        } catch (Exception e) {
            throw new IllegalStateException("The evaluation set could not be read", e);
        }
        if (set.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "No conversation matches");
        UUID id = UUID.randomUUID();
        Map<String, Object> run = new ConcurrentHashMap<>();
        run.put("id", id); run.put("tenantId", tenantId); run.put("model", m); run.put("status", "RUNNING"); run.put("total", set.size()); run.put("done", 0);
        run.put("startedAt", java.time.Instant.now().toString());
        List<Map<String, Object>> results = java.util.Collections.synchronizedList(new ArrayList<>());
        run.put("results", results);
        runs.put(id, run);
        worker.execute(() -> {
            try {
                for (JsonNode c : set) { results.add(play(tenantId, m, id, c, false, null)); run.put("done", results.size()); }
                run.put("summary", summary(results));
                run.put("status", "DONE");
                keep(id, tenantId, m, kind, run);
            } catch (RuntimeException e) {
                log.warn("AI evaluation failed", e);
                run.put("status", "FAILED"); run.put("error", String.valueOf(e.getMessage()));
                keep(id, tenantId, m, kind, run);
            }
        });
        return id;
    }

    /** Drop that raises an alert: five points or more below the last run of the same kind on the same model. */
    static final int ALERT_DROP = 5;

    @SuppressWarnings("unchecked")
    private void keep(UUID id, UUID tenantId, String model, String kind, Map<String, Object> run) {
        try {
            Map<String, Object> sum = (Map<String, Object>) run.get("summary");
            Integer pct = sum == null ? null : ((Number) sum.get("overallPct")).intValue();
            Integer checks = sum == null ? null : ((Number) sum.get("checks")).intValue();
            // nothing graded (the judge could not be reached): not a score, so never compared
            List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");
            boolean graded = results != null && results.stream().anyMatch(r -> r.get("checks") instanceof List<?> l && !l.isEmpty());
            String status = "DONE".equals(run.get("status")) && graded ? "DONE" : "FAILED";
            Integer before = jdbc.sql("SELECT overall_pct FROM chat.ai_eval_runs WHERE tenant_id = :t AND model = :m AND kind = :k AND status = 'DONE' ORDER BY finished_at DESC LIMIT 1")
                    .param("t", tenantId).param("m", model).param("k", kind).query(Integer.class).optional().orElse(null);
            jdbc.sql("INSERT INTO chat.ai_eval_runs (id, tenant_id, model, kind, overall_pct, checks, summary, status, started_at) VALUES (:i, :t, :m, :k, :p, :c, CAST(:s AS jsonb), :st, CAST(:at AS timestamptz))")
                    .param("i", id).param("t", tenantId).param("m", model).param("k", kind).param("p", "DONE".equals(status) ? pct : null).param("c", checks)
                    .param("s", JSON.writeValueAsString(sum == null ? Map.of() : sum)).param("st", status).param("at", String.valueOf(run.get("startedAt"))).update();
            if ("DONE".equals(status) && before != null && pct != null && before - pct >= ALERT_DROP && alerts != null)
                alerts.accept("AI_QUALITY_DROP", "جودة " + model + " نزلت من " + before + "% لـ " + pct + "% في الاختبار الدوري / " + model + " quality fell from " + before + "% to " + pct + "% in the " + kind.toLowerCase() + " check");
        } catch (Exception e) {
            log.warn("Could not keep the evaluation result", e);
        }
    }

    /** Where a quality drop is reported (the platform owners' phones); set by the scheduler. */
    private java.util.function.BiConsumer<String, String> alerts;

    void onAlert(java.util.function.BiConsumer<String, String> alerts) { this.alerts = alerts; }

    public Map<String, Object> run(UUID id) {
        var r = runs.get(id);
        if (r == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Evaluation not found");
        return r;
    }

    /** The golden conversations of {@code ai-eval.json} and the order scenarios of {@code ai-eval-orders.json} (ids from 900). */
    List<JsonNode> goldenSet() {
        try {
            List<JsonNode> all = new ArrayList<>();
            JSON.readTree(getClass().getResourceAsStream("/ai-eval.json")).path("conversations").forEach(all::add);
            JSON.readTree(getClass().getResourceAsStream("/ai-eval-orders.json")).path("conversations").forEach(all::add);
            return all;
        } catch (Exception e) {
            throw new IllegalStateException("The evaluation set could not be read", e);
        }
    }

    /**
     * Plays one conversation and grades it. {@code lean} runs it with the short list of important rules instead of the full prompt, {@code temperature} (when set) is the same for every
     * compared model (see {@link ChatService.EvalScope}). The result carries, besides the judge's verdict:
     * {@code turns}: for each customer message the reply, its wall-clock time, whether a model answered (or code did: instant answers, summaries), the model calls and tokens, the tools it
     * called and which of them failed, model failures, fallbacks and second opinions (another model asked the same message; its tokens are in {@code secondOpinions}, never in {@code usage});
     * {@code usage}: the answering model's own totals; {@code toolCalls}; and, for a scenario with an {@code order} (a customer who really orders a known product), {@code orderCheck}:
     * code-checked facts about the order (right variant and quantity in the summary, the contact details and payment as typed, placed after the confirmation, placed once, total not below the
     * goods). {@code judgeTokens} is what the judge used, which is not counted in {@code usage}.
     */
    public Map<String, Object> play(UUID tenantId, String model, UUID runId, JsonNode c, boolean lean, Double temperature) {
        String ext = "eval-" + runId.toString().substring(0, 8) + "-" + c.path("id").asInt();
        String name = c.hasNonNull("displayName") ? c.path("displayName").asText() : null;
        List<String> lines = new ArrayList<>();
        for (JsonNode t : c.path("customer")) lines.add(t.asText());
        List<String> turns = c.path("burst").asBoolean(false) ? List.of(String.join("\n", lines)) : lines;
        List<Map<String, String>> transcript = new ArrayList<>();
        List<Map<String, Object>> turnRecords = new ArrayList<>();
        List<JsonNode> snapshots = new ArrayList<>();
        List<Boolean> placedAt = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.path("id").asInt()); out.put("category", c.path("category").asText());
        ChatService.EvalScope scope = new ChatService.EvalScope(lean, temperature);
        try {
            chat.setTestModel(tenantId, ext, model);
            for (String t : turns) {
                int r0 = scope.rounds.size(), t0 = scope.toolCalls.size(), f0 = scope.failures.size(), s0 = scope.secondOpinions.size(), m0 = scope.modelReplies, fb0 = scope.fallbacks;
                long started = System.nanoTime();
                String reply;
                boolean placed = false;
                try {
                    var rep = chat.inEvalScope(scope, () -> chat.reply(tenantId, "TEST", ext, null, t, name));
                    reply = rep.text();
                    placed = rep.order() != null;
                } catch (RuntimeException e) { reply = "[error: " + e.getMessage() + "]"; }
                long ms = (System.nanoTime() - started) / 1_000_000;
                transcript.add(Map.of("customer", t, "seller", reply == null ? "" : reply));
                placedAt.add(placed);
                if (c.has("order")) {
                    try {
                        String pending = jdbc.sql("SELECT pending::text FROM chat.conversations WHERE tenant_id = :t AND channel = 'TEST' AND external_id = :e").param("t", tenantId).param("e", ext).query(String.class).optional().orElse(null);
                        snapshots.add(pending == null ? null : JSON.readTree(pending));
                    } catch (Exception e) { snapshots.add(null); }
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                long in = 0, outTok = 0, cached = 0;
                for (int k = r0; k < scope.rounds.size(); k++) { var u = scope.rounds.get(k).usage(); in += u.input(); outTok += u.output(); cached += u.cached(); }
                int errs = 0;
                for (int k = t0; k < scope.toolCalls.size(); k++) if (Boolean.FALSE.equals(scope.toolCalls.get(k).get("ok"))) errs++;
                rec.put("ms", ms);
                rec.put("path", scope.modelReplies == m0 ? "CODE" : scope.failures.size() > f0 ? "MODEL_FAILED" : "MODEL");
                rec.put("modelCalls", scope.rounds.size() - r0); rec.put("inputTokens", in); rec.put("outputTokens", outTok); rec.put("cachedTokens", cached);
                rec.put("toolCalls", scope.toolCalls.size() - t0); rec.put("toolErrors", errs); rec.put("modelFailures", scope.failures.size() - f0);
                rec.put("fallbacks", scope.fallbacks - fb0); rec.put("secondOpinions", scope.secondOpinions.size() - s0);
                rec.put("secondOpinionAdopted", scope.secondOpinions.subList(s0, scope.secondOpinions.size()).stream().anyMatch(x -> Boolean.TRUE.equals(x.get("adopted"))));
                if (reply != null && (reply.startsWith("⚠️") || reply.startsWith("[error:"))) rec.put("errorReply", true);
                turnRecords.add(rec);
            }
        } finally {
            chat.reset(tenantId, "TEST", ext);
        }
        out.put("transcript", transcript);
        out.put("turns", turnRecords);
        long in = 0, outTok = 0, cached = 0, write = 0;
        for (var r : scope.rounds) { in += r.usage().input(); outTok += r.usage().output(); cached += r.usage().cached(); write += r.usage().cacheWrite(); }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("customerMessages", turns.size()); usage.put("modelReplies", scope.modelReplies); usage.put("modelCalls", scope.rounds.size()); usage.put("fallbacks", scope.fallbacks);
        usage.put("inputTokens", in); usage.put("outputTokens", outTok); usage.put("cachedTokens", cached); usage.put("cacheWriteTokens", write); usage.put("millis", scope.millis);
        usage.put("systemChars", scope.systemChars); usage.put("toolsHash", scope.toolsHash);
        out.put("usage", usage);
        out.put("toolCalls", scope.toolCalls);
        out.put("modelFailures", scope.failures);
        out.put("secondOpinions", scope.secondOpinions);
        out.put("answeredBy", scope.answeredBy);
        if (c.has("order")) out.put("orderCheck", orderCheck(tenantId, c.path("order"), snapshots, placedAt, transcript));
        List<String> expect = new ArrayList<>();
        for (JsonNode e : c.path("expect")) expect.add(e.asText());
        out.putAll(grade(transcript, expect, name));
        return out;
    }

    /**
     * What really happened to an order a customer was asked to place, checked by code against the shop's data and what the customer typed, not by the judge. {@code snapshots} is the
     * conversation's pending order after each customer message (null when none), {@code placedAt} whether that message ended with the order placed (a test order on the TEST channel).
     */
    private Map<String, Object> orderCheck(UUID tenantId, JsonNode want, List<JsonNode> snapshots, List<Boolean> placedAt, List<Map<String, String>> transcript) {
        Map<String, Object> ck = new LinkedHashMap<>();
        var row = jdbc.sql("SELECT id, price_minor FROM commerce.product_variants WHERE tenant_id = :t AND sku = :s").param("t", tenantId).param("s", want.path("sku").asText()).query().listOfRows().stream().findFirst().orElse(null);
        int qty = want.path("qty").asInt(1);
        JsonNode last = null;
        int firstSummary = -1;
        for (int i = 0; i < snapshots.size(); i++) if (snapshots.get(i) != null) { last = snapshots.get(i); if (firstSummary < 0) firstSummary = i; }
        int placements = (int) placedAt.stream().filter(b -> b).count();
        int placedTurn = placedAt.indexOf(true);
        ck.put("summaryShown", last != null);
        boolean items = false;
        if (last != null && row != null && last.path("items").isArray() && last.path("items").size() == 1) {
            JsonNode it = last.path("items").get(0);
            items = it.path("variantId").asText().equals(String.valueOf(row.get("id"))) && it.path("quantity").asInt() == qty;
        }
        ck.put("itemAndQuantityCorrect", items);
        String phone = last == null ? "" : last.path("phone").asText("").replaceAll("\\D", "");
        String wantPhone = want.path("phone").asText("").replaceAll("\\D", "");
        boolean contact = last != null && phone.endsWith(wantPhone.substring(Math.max(0, wantPhone.length() - 10))) && last.path("name").asText("").contains(want.path("name").asText("~"));
        String addr = last == null ? "" : last.path("line1").asText("") + " " + last.path("area").asText("") + " " + last.path("governorate").asText("");
        for (JsonNode tok : want.path("addressContains")) if (!addr.contains(tok.asText())) contact = false;
        ck.put("contactDetailsCorrect", contact);
        ck.put("paymentCorrect", last != null && want.path("payment").asText("").equalsIgnoreCase(last.path("paymentMethod").asText("")));
        ck.put("placedOnce", placements == 1);
        ck.put("placedAfterSummary", placedTurn >= 0 && firstSummary >= 0 && placedTurn > firstSummary);
        boolean sane = false;
        if (last != null && row != null) sane = last.path("totalMinor").asLong(-1) >= ((Number) row.get("price_minor")).longValue() * qty;
        ck.put("totalNotBelowGoods", sane);
        ck.put("allCorrect", ck.values().stream().allMatch(Boolean.TRUE::equals));
        return ck;
    }

    private Map<String, Object> grade(List<Map<String, String>> transcript, List<String> expect, String name) {
        StringBuilder b = new StringBuilder(name == null ? "" : "The customer's chat-app name: " + name + "\n");
        for (var t : transcript) b.append("Customer: ").append(t.get("customer")).append("\nSalesperson: ").append(t.get("seller")).append("\n");
        b.append("\nRUBRIC:\n");
        for (String e : expect) b.append("- ").append(e).append("\n");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Turn t = channelAi.forChannel("TEST", "anthropic").next(JUDGE, List.of(Turn.user(b.toString())), List.of());
            if (t.usage() != null) out.put("judgeTokens", Map.of("input", t.usage().input(), "output", t.usage().output()));
            String raw = t.text() == null ? "" : t.text().trim().replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
            JsonNode n = JSON.readTree(raw.substring(Math.max(0, raw.indexOf('{')), raw.lastIndexOf('}') + 1));
            List<Map<String, Object>> checks = new ArrayList<>();
            int pass = 0;
            for (JsonNode ch : n.path("checks")) {
                boolean p = ch.path("pass").asBoolean(false);
                if (p) pass++;
                checks.add(Map.of("expect", ch.path("expect").asText(), "pass", p, "why", ch.path("why").asText("")));
            }
            out.put("checks", checks); out.put("passed", pass); out.put("of", Math.max(checks.size(), expect.size())); out.put("note", n.path("note").asText(""));
        } catch (Exception e) {
            out.put("checks", List.of()); out.put("passed", 0); out.put("of", expect.size()); out.put("note", "The judge could not grade this one: " + e.getMessage());
        }
        return out;
    }

    /** Pass rate per category and overall, in percent. */
    static Map<String, Object> summary(List<Map<String, Object>> results) {
        Map<String, int[]> by = new java.util.TreeMap<>();
        int pass = 0, of = 0;
        for (var r : results) {
            int p = ((Number) r.get("passed")).intValue(), o = ((Number) r.get("of")).intValue();
            by.computeIfAbsent(String.valueOf(r.get("category")), k -> new int[2]);
            by.get(String.valueOf(r.get("category")))[0] += p;
            by.get(String.valueOf(r.get("category")))[1] += o;
            pass += p; of += o;
        }
        Map<String, Object> cats = new LinkedHashMap<>();
        by.forEach((k, v) -> cats.put(k, v[1] == 0 ? 0 : Math.round(v[0] * 100.0 / v[1])));
        return Map.of("overallPct", of == 0 ? 0 : Math.round(pass * 100.0 / of), "byCategory", cats, "checks", of);
    }
}

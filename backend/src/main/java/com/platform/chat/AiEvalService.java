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
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ai-eval"); t.setDaemon(true); return t; });
    private final Map<UUID, Map<String, Object>> runs = new ConcurrentHashMap<>();

    public AiEvalService(ChatService chat, ChannelAi channelAi) {
        this.chat = chat;
        this.channelAi = channelAi;
    }

    static final String JUDGE = """
            You grade an online shop's AI salesperson. You get one test conversation (the customer's messages and the salesperson's replies) and the rubric: what a great Egyptian salesperson does here.
            For each rubric line decide pass or fail, strictly, from the replies only. Also fail any line if a reply invents product facts, urgency or discounts tied to ordering now, uses the wrong gender,
            offers kids' products to an adult buying for themself, ignores a question, or uses odd or wrong words.
            Answer with JSON only: {"checks":[{"expect":"<rubric line>","pass":true|false,"why":"<few words>"}],"note":"<one sentence on what would make it better>"}
            """;

    /** Starts a run on {@code tenantId} with {@code model} ("default", "glm", "anthropic", "qwen"); returns its id. */
    public UUID start(UUID tenantId, String model, String only) {
        if (runs.values().stream().anyMatch(r -> "RUNNING".equals(r.get("status")))) throw BusinessException.conflict("EVAL_RUNNING", "An evaluation is already running, wait for it to finish");
        String m = model == null || model.isBlank() ? "default" : model;
        if (!ChatService.TRAIN_MODELS.contains(m)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown model");
        List<JsonNode> set = new ArrayList<>();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (only != null && !only.isBlank()) for (String s : only.split(",")) try { ids.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { /* skipped */ }
        try {
            for (JsonNode c : JSON.readTree(getClass().getResourceAsStream("/ai-eval.json")).path("conversations"))
                if (ids.isEmpty() || ids.contains(c.path("id").asInt()) || c.path("category").asText().equals(only)) set.add(c);
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
                for (JsonNode c : set) { results.add(play(tenantId, m, id, c)); run.put("done", results.size()); }
                run.put("summary", summary(results));
                run.put("status", "DONE");
            } catch (RuntimeException e) {
                log.warn("AI evaluation failed", e);
                run.put("status", "FAILED"); run.put("error", String.valueOf(e.getMessage()));
            }
        });
        return id;
    }

    public Map<String, Object> run(UUID id) {
        var r = runs.get(id);
        if (r == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Evaluation not found");
        return r;
    }

    private Map<String, Object> play(UUID tenantId, String model, UUID runId, JsonNode c) {
        String ext = "eval-" + runId.toString().substring(0, 8) + "-" + c.path("id").asInt();
        String name = c.hasNonNull("displayName") ? c.path("displayName").asText() : null;
        List<String> lines = new ArrayList<>();
        for (JsonNode t : c.path("customer")) lines.add(t.asText());
        List<String> turns = c.path("burst").asBoolean(false) ? List.of(String.join("\n", lines)) : lines;
        List<Map<String, String>> transcript = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.path("id").asInt()); out.put("category", c.path("category").asText());
        try {
            chat.setTestModel(tenantId, ext, model);
            for (String t : turns) {
                String reply;
                try { reply = chat.reply(tenantId, "TEST", ext, null, t, name).text(); } catch (RuntimeException e) { reply = "[error: " + e.getMessage() + "]"; }
                transcript.add(Map.of("customer", t, "seller", reply == null ? "" : reply));
            }
        } finally {
            chat.reset(tenantId, "TEST", ext);
        }
        out.put("transcript", transcript);
        List<String> expect = new ArrayList<>();
        for (JsonNode e : c.path("expect")) expect.add(e.asText());
        out.putAll(grade(transcript, expect, name));
        return out;
    }

    private Map<String, Object> grade(List<Map<String, String>> transcript, List<String> expect, String name) {
        StringBuilder b = new StringBuilder(name == null ? "" : "The customer's chat-app name: " + name + "\n");
        for (var t : transcript) b.append("Customer: ").append(t.get("customer")).append("\nSalesperson: ").append(t.get("seller")).append("\n");
        b.append("\nRUBRIC:\n");
        for (String e : expect) b.append("- ").append(e).append("\n");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Turn t = channelAi.forChannel("TEST", "anthropic").next(JUDGE, List.of(Turn.user(b.toString())), List.of());
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

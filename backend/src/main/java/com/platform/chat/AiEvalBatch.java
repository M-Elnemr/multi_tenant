package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A model comparison that runs inside the backend, so it needs no login: drop a plan file at {@code <files dir>/eval/plan.json} on the server and it is picked up within 30 seconds.
 * Everything runs on the TEST channel (nothing is ordered, billed or kept) against the shops named in the plan, every model on its own thread, and the raw result of every conversation
 * (transcript, judge verdict, calls, tokens, time) goes to {@code <files dir>/eval/<plan id>/report.json}, rewritten after each conversation. Only someone with access to the server's files can start it.
 *
 * <pre>{"id":"cmp1","temperature":0.3,"maxOutputTokens":2000,"models":["glm","deepseek","qwen"],"modes":["full","lean"],"stores":{"tech-zone":[1,2,3,901],"nile-style":[4,5,911]}}</pre>
 * {@code full} is the production prompt; {@code lean} keeps only the important rules (see {@link ChatService#LEAN_RULES}); both use the same tools, RAG and code-side checks.
 */
@Component
public class AiEvalBatch {
    private static final Logger log = LoggerFactory.getLogger(AiEvalBatch.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final AiEvalService eval;
    private final ChannelAi channelAi;
    private final KnowledgeIndex knowledge;
    private final JdbcClient jdbc;
    private final Path dir;
    private volatile boolean running;

    public AiEvalBatch(AiEvalService eval, ChannelAi channelAi, KnowledgeIndex knowledge, JdbcClient jdbc, @Value("${app.files.dir:./uploads}") String filesDir) {
        this.eval = eval;
        this.channelAi = channelAi;
        this.knowledge = knowledge;
        this.jdbc = jdbc;
        this.dir = Path.of(filesDir, "eval");
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    void poll() {
        Path plan = dir.resolve("plan.json");
        if (running || !Files.isRegularFile(plan)) return;
        try {
            Path taken = dir.resolve("plan.running.json");
            Files.move(plan, taken, StandardCopyOption.REPLACE_EXISTING);
            JsonNode p = JSON.readTree(Files.readString(taken));
            running = true;
            Thread t = new Thread(() -> { try { execute(p); } finally { running = false; } }, "ai-eval-batch");
            t.setDaemon(true);
            t.start();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not start the evaluation plan: {}", e.toString());
        }
    }

    private final Object lock = new Object();

    private void execute(JsonNode plan) {
        String id = plan.path("id").asText("run-" + System.currentTimeMillis());
        Path out = dir.resolve(id);
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> cells = new ArrayList<>();
        report.put("id", id); report.put("status", "RUNNING"); report.put("startedAt", Instant.now().toString()); report.put("plan", JSON.convertValue(plan, Map.class)); report.put("cells", cells);
        try {
            Files.createDirectories(out);
            List<String> models = new ArrayList<>(), modes = new ArrayList<>();
            plan.path("models").forEach(n -> models.add(n.asText()));
            plan.path("modes").forEach(n -> modes.add(n.asText()));
            List<JsonNode> golden = eval.goldenSet();
            // every compared model runs with the same temperature (the plan's), and the report shows what each one is set to
            Double temperature = plan.hasNonNull("temperature") ? plan.path("temperature").asDouble() : null;
            report.put("temperature", temperature);
            Integer maxOutput = plan.hasNonNull("maxOutputTokens") ? plan.path("maxOutputTokens").asInt() : null;
            report.put("maxOutputTokens", maxOutput);
            report.put("ragHealthAtStart", knowledge.health());   // the embedding provider's counters since the backend started
            Map<String, Object> settings = new LinkedHashMap<>();
            for (String model : models) settings.put(model, channelAi.forChannel("TEST", model).settings());
            report.put("modelSettings", settings);
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, models.size()));
            for (String model : models) pool.submit(() -> {
                try {
                    plan.path("stores").fields().forEachRemaining(store -> {
                        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", store.getKey()).query(UUID.class).single();
                        for (String mode : modes) {
                            Map<String, Object> cell = new LinkedHashMap<>();
                            List<Map<String, Object>> results = new ArrayList<>();
                            cell.put("store", store.getKey()); cell.put("model", model); cell.put("mode", mode); cell.put("startedAt", Instant.now().toString()); cell.put("status", "RUNNING"); cell.put("results", results);
                            synchronized (lock) { cells.add(cell); }
                            UUID runId = UUID.randomUUID();
                            for (JsonNode idNode : store.getValue()) {
                                JsonNode c = golden.stream().filter(g -> g.path("id").asInt() == idNode.asInt()).findFirst().orElse(null);
                                if (c == null || (c.hasNonNull("shop") && !c.path("shop").asText().equals(store.getKey()))) continue;
                                Map<String, Object> r;
                                try { r = eval.play(tenant, model, runId, c, "lean".equals(mode), temperature, maxOutput); } catch (RuntimeException e) { r = new LinkedHashMap<>(Map.of("id", c.path("id").asInt(), "error", String.valueOf(e.getMessage()))); }
                                synchronized (lock) { results.add(r); flush(out, report); }
                            }
                            synchronized (lock) { cell.put("status", "DONE"); cell.put("finishedAt", Instant.now().toString()); flush(out, report); }
                        }
                    });
                } catch (RuntimeException e) {
                    log.warn("Evaluation thread for {} failed", model, e);
                    synchronized (lock) { report.put("error-" + model, String.valueOf(e)); }
                }
            });
            pool.shutdown();
            pool.awaitTermination(12, TimeUnit.HOURS);
            report.put("status", "DONE");
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Evaluation plan failed", e);
            report.put("status", "FAILED"); report.put("error", String.valueOf(e));
        }
        report.put("finishedAt", Instant.now().toString());
        report.put("ragHealthAtEnd", knowledge.health());
        synchronized (lock) { flush(out, report); }
        log.info("Evaluation plan {} finished: {}", id, report.get("status"));
    }

    private void flush(Path out, Map<String, Object> report) {
        try {
            Path tmp = out.resolve("report.json.tmp");
            Files.writeString(tmp, JSON.writeValueAsString(report), StandardCharsets.UTF_8);
            Files.move(tmp, out.resolve("report.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not write the evaluation report: {}", e.toString());
        }
    }
}

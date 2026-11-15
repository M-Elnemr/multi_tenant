package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.platform.FakeAiProvider;
import com.platform.IntegrationTestBase;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where a reply's code and database time goes in six realistic situations, with an instant model (FakeAiProvider) so only OUR work is measured (model time is seconds and dominates in production).
 * Prints a table with BENCH_OVERHEAD=1. Small samples (12 replies each, the first dropped as warm-up): p95 is close to the slowest reply. Statements per reply are approximate (Postgres
 * transaction counters of the test database, so scheduled jobs add a little).
 */
class ReplyPerfProfileTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    UUID shop(int products) throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        for (int i = 0; i < products; i++) {
            StringBuilder v = new StringBuilder();
            for (String c : List.of("أبيض", "أسود", "كحلي")) for (String sz : List.of("M", "L", "XL"))
                v.append(v.length() == 0 ? "" : ",").append("{\"sku\":\"S%d-%s\",\"priceMinor\":%d,\"optionValues\":{\"اللون\":\"%s\",\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}".formatted(i, uniq(), 15000 + i * 100, c, sz, branch));
            onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"name\":\"قميص قطن موديل %d %s\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أبيض\",\"أسود\",\"كحلي\"]},{\"name\":\"المقاس\",\"values\":[\"M\",\"L\",\"XL\"]}],\"variants\":[%s]}".formatted(i, uniq(), v)).andReturn();
        }
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    static final Pattern NUM = Pattern.compile("(?:^|[ (])(total|prep|prompt|products|flow|post|replyContext|memoryFacts|earlierDigest)=(\\d+)");

    Map<String, Long> parse(String timeline) {
        Map<String, Long> m = new LinkedHashMap<>();
        Matcher x = NUM.matcher(timeline);
        while (x.find()) m.putIfAbsent(x.group(1), Long.parseLong(x.group(2)));
        return m;
    }

    static double pct(List<Long> v, double p) { List<Long> s = new ArrayList<>(v); Collections.sort(s); return s.isEmpty() ? 0 : s.get((int) Math.min(s.size() - 1, Math.ceil(p * s.size()) - 1)); }

    long xacts() { return jdbc.sql("SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()").query(Long.class).single(); }

    interface One { ChatService.Reply run(int i); }

    record Result(String name, List<Long> total, Map<String, List<Long>> parts, double statements) {}

    Result measure(String name, boolean searchTool, One one) throws Exception {
        List<Long> total = new ArrayList<>();
        Map<String, List<Long>> parts = new LinkedHashMap<>();
        for (int i = 0; i < 13; i++) {
            FakeAiProvider.reset();
            if (searchTool) { FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "قميص قطن"))); FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا قميص قطن بسعر مناسب، تحب أحجزهولك؟")); FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":true}")); }
            else FakeAiProvider.SCRIPT.add(turns -> Turn.model("أهلا بيك، تحب تشوف إيه؟"));
            one.run(i);
            Map<String, Long> t = parse(ReplyTimeline.takeLast());
            if (i == 0) continue;   // warm-up
            total.add(t.getOrDefault("total", 0L));
            for (var e : t.entrySet()) if (!e.getKey().equals("total")) parts.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        Thread.sleep(1500);
        long before = xacts();
        for (int i = 0; i < 6; i++) { FakeAiProvider.reset(); FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام")); one.run(100 + i); ReplyTimeline.takeLast(); }
        Thread.sleep(1800);
        double statements = (xacts() - before) / 6.0;
        return new Result(name, total, parts, statements);
    }

    @Test
    void whereTheCodeAndDatabaseTimeGoesInSixSituations() throws Exception {
        UUID small = shop(12), heavy = shop(60);
        // a long chat: 4000 messages, the customer's budget at the very start
        String longExt = "perf-long-" + uniq();
        UUID longConv = chat.reply(small, "WHATSAPP", longExt, null, "ميزانيتي ٦٠٠ جنيه ومقاسي XL", null).conversationId();
        long oldId = jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) VALUES (:c, 'assistant', 'عندنا قميص قطن موديل 3 بسعر مناسب') RETURNING id").param("c", longConv).query(Long.class).single();
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content) SELECT :c, CASE WHEN g % 2 = 1 THEN 'user' ELSE 'assistant' END, 'ملء رقم ' || g FROM generate_series(1, 4000) g").param("c", longConv).update();
        jdbc.sql("ANALYZE chat.messages").update();
        List<Result> out = new ArrayList<>();
        out.add(measure("short chat, 12 products, product search", true, i -> chat.reply(small, "WHATSAPP", "perf-s-" + uniq(), null, "عايز قميص قطن بكام؟", null)));
        out.add(measure("product-heavy, 60 products, product search", true, i -> chat.reply(heavy, "WHATSAPP", "perf-h-" + uniq(), null, "عايز قميص قطن بكام؟", null)));
        out.add(measure("4000-message chat, memory + digest", false, i -> chat.reply(small, "WHATSAPP", longExt, null, "الجاكيت ده بكام؟ وفاكر مقاسي؟", null)));
        out.add(measure("4000-message chat, reply to the oldest message", false, i -> chat.reply(small, "WHATSAPP", longExt, null, "هل ده متاح؟", null, new ChatService.ReplyInput(List.of(ReplyRef.ofId(oldId)), List.of("m" + uniq())))));
        out.add(measure("burst of 6 lines, 3 with their own reply", false, i -> chat.reply(small, "WHATSAPP", longExt, null, "بكام ده\nوده\nوفيه ازرق؟\nوالتاني\nمقاس كام\nتمام", null,
                new ChatService.ReplyInput(List.of(ReplyRef.ofId(oldId).withLine("بكام ده"), ReplyRef.ofId(oldId).withLine("والتاني"), ReplyRef.ofId(oldId).withLine("مقاس كام")), List.of("a" + uniq(), "b" + uniq())))));
        out.add(measure("comparison: two searches + knowledge lookup", true, i -> chat.reply(heavy, "WHATSAPP", "perf-c-" + uniq(), null, "ايه الفرق بين قميص موديل 1 وموديل 2؟", null)));
        StringBuilder t = new StringBuilder(String.format("%n%-48s | %7s %7s | %9s %9s %9s %9s %9s | %s%n", "scenario (12 replies, instant model)", "p50 ms", "p95 ms", "prompt p50", "reply-ctx", "memory", "digest", "products", "statements/reply"));
        for (Result r : out) {
            java.util.function.Function<String, String> p50 = k -> r.parts().containsKey(k) ? String.format("%.0f", pct(r.parts().get(k), 0.5)) : "-";
            t.append(String.format("%-48s | %7.0f %7.0f | %9s %9s %9s %9s %9s | %.0f%n", r.name(), pct(r.total(), 0.5), pct(r.total(), 0.95), p50.apply("prompt"), p50.apply("replyContext"), p50.apply("memoryFacts"), p50.apply("earlierDigest"), p50.apply("products"), r.statements()));
            assertThat(r.total()).hasSize(12);
        }
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.println("PERF_PROFILE" + t);
        FakeAiProvider.reset();
    }
}

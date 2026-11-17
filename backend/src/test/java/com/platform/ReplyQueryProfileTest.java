package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** Profiling aid and regression guard: how many SQL statements one reply costs, and which ChatService method issues each (tests only; nothing here ships). */
@Import(ReplyQueryProfileTest.Counting.class)
@TestPropertySource(properties = {"app.ai.review.enabled=true"})
class ReplyQueryProfileTest extends IntegrationTestBase {
    static final AtomicBoolean ON = new AtomicBoolean();
    static final Map<String, Integer> BY_CALLER = new ConcurrentHashMap<>();
    static final Map<String, Integer> BY_SQL = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Counting {
        @Bean static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds) || Proxy.isProxyClass(bean.getClass())) return bean;
                    return Proxy.newProxyInstance(ds.getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                        Object r = m.invoke(ds, a);
                        if (r instanceof java.sql.Connection c && m.getName().equals("getConnection")) return wrap(c);
                        return r;
                    });
                }
            };
        }
        static java.sql.Connection wrap(java.sql.Connection c) {
            return (java.sql.Connection) Proxy.newProxyInstance(c.getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (p, m, a) -> {
                if (ON.get() && (m.getName().equals("prepareStatement") || m.getName().equals("createStatement")) && a != null && a.length > 0 && a[0] instanceof String sql) record(sql);
                try { return m.invoke(c, a); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
        }
        static void record(String sql) {
            String caller = "?";
            for (StackTraceElement f : new Throwable().getStackTrace()) {
                if (f.getClassName().startsWith("com.platform.") && !f.getClassName().contains("ReplyQueryProfileTest")) { caller = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName(); break; }
            }
            BY_CALLER.merge(caller, 1, Integer::sum);
            BY_SQL.merge(sql.replaceAll("\\s+", " ").substring(0, Math.min(110, sql.replaceAll("\\s+", " ").length())), 1, Integer::sum);
        }
    }

    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); BY_CALLER.clear(); BY_SQL.clear(); }

    @Test
    void profile() throws Exception {
        Tenant shop = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", shop.slug()).query(UUID.class).single();
        String branch = com.jayway.jsonpath.JsonPath.read(onHost(shop.host(), shop.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        int products = Integer.parseInt(System.getenv().getOrDefault("PROFILE_PRODUCTS", "30"));
        for (int i = 0; i < products; i++)
            onHost(shop.host(), shop.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"handbags","audience":"WOMEN","name":"شنطة %1$d %2$s","options":[{"name":"اللون","values":["أسود","بني"]}],
                     "variants":[{"sku":"A-%2$s-%1$d","priceMinor":62000,"optionValues":{"اللون":"أسود"},"stock":[{"branchId":"%3$s","quantity":5}]},
                                 {"sku":"B-%2$s-%1$d","priceMinor":64000,"optionValues":{"اللون":"بني"},"stock":[{"branchId":"%3$s","quantity":4}]}]}
                    """.formatted(i, uniq(), branch)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        int lastTotal = Integer.MAX_VALUE;
        for (int i = 0; i < 3; i++) {   // the same customer chatting: first message warms the caches, the next ones show the steady state
            FakeAiProvider.reset(); BY_CALLER.clear(); BY_SQL.clear();
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا شنط جلد حلوة، تحبي لون إيه؟"));
            FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":true}"));
            ON.set(true);
            long t0 = System.nanoTime();
            chat.reply(id, "WEB", "prof-fixed", null, "عندكم شنط؟ " + i);
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            ON.set(false);
            System.out.println("PROFILE WALL reply#" + i + " backend-only (instant fake model) ms=" + wallMs);
            int total = BY_CALLER.values().stream().mapToInt(Integer::intValue).sum();
            if (i == 1) lastTotal = total;   // reply #2 also carries the outbox dispatch of the earlier ones, so the steady state is read from #1
            System.out.println("=== PROFILE reply#" + i + " total SQL statements: " + total);
            new TreeMap<>(BY_CALLER).entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14).forEach(e -> System.out.println("PROFILE   " + e.getValue() + " x " + e.getKey()));
            BY_SQL.entrySet().stream().filter(e -> e.getValue() > 1).sorted((a, b) -> b.getValue() - a.getValue()).limit(14).forEach(e -> System.out.println("PROFILE   DUP " + e.getValue() + " x " + e.getKey()));
        }
        // before the batched catalog digest a 30-product shop cost about 300 statements per reply (eight per product); the guard keeps the count near the ~90 of today
        assertThat(lastTotal).as("SQL statements for one warm reply of a 30-product shop").isLessThan(140);
    }
}

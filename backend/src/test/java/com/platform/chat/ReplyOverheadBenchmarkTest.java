package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.FakeAiProvider;
import com.platform.IntegrationTestBase;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * How long the code and the database take for one reply when the model answers instantly (FakeAiProvider): the phases of ReplyTimeline for shops of different sizes. It prints; the assertions only
 * check that the timeline is produced. Run it with BENCH_OVERHEAD=1 to see the numbers.
 */
@TestPropertySource(properties = "app.ai.review.enabled=true")
class ReplyOverheadBenchmarkTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    UUID shop(int products) throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        for (int i = 0; i < products; i++) {
            StringBuilder v = new StringBuilder();
            for (String c : List.of("أبيض", "أسود", "كحلي")) for (String sz : List.of("M", "L", "XL"))
                v.append(v.length() == 0 ? "" : ",").append("{\"sku\":\"S%d-%s\",\"priceMinor\":%d,\"optionValues\":{\"اللون\":\"%s\",\"المقاس\":\"%s\"},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}".formatted(i, uniq(), 15000 + i * 100, c, sz, branch));
            onHost(t.host(), t.access(), "POST", "/api/v1/store/products", "{\"name\":\"قميص قطن موديل %d %s\",\"taxonomySlug\":\"shirts-tops\",\"audience\":\"MEN\",\"options\":[{\"name\":\"اللون\",\"values\":[\"أبيض\",\"أسود\",\"كحلي\"]},{\"name\":\"المقاس\",\"values\":[\"M\",\"L\",\"XL\"]}],\"variants\":[%s]}".formatted(i, uniq(), v)).andExpect(status().isCreated());
        }
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    @Test
    void theTimelineShowsWhereTheCodeAndDatabaseTimeGoes() throws Exception {
        boolean show = System.getenv("BENCH_OVERHEAD") != null;
        for (int n : new int[] {12, 40, 80}) {
            UUID id = shop(n);
            List<String> lines = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                FakeAiProvider.reset();
                FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "قميص قطن")));
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا قميص قطن موديل 1 بـ 151 جنيه، تحب أحجزهولك؟"));
                FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"ok\":true}"));
                chat.reply(id, "WEB", "ovh-" + uniq(), null, "عايز قميص قطن بكام؟");
                String t = ReplyTimeline.takeLast();
                assertThat(t).as("a timeline per reply").startsWith("total=").contains("prep=").contains("prompt=").contains("flow=").contains("post=");
                if (r > 0) lines.add(t);   // the first reply warms the caches
            }
            if (show) { System.out.println("OVERHEAD " + n + " products:"); lines.forEach(l -> System.out.println("   " + l)); }
        }
        FakeAiProvider.reset();
    }
}

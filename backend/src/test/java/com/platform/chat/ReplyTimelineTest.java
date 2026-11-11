package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The per-reply timeline: phases with the model calls, tools and checks inside them; nothing outside a reply; every reply on its own thread keeps its own. */
class ReplyTimelineTest {
    @AfterEach
    void clean() { ReplyTimeline.finish("TEST", "x"); ReplyTimeline.takeLast(); }

    @Test
    void phasesCarryTheCallsToolsAndChecksThatHappenedInsideThem() throws Exception {
        ReplyTimeline.begin();
        ReplyTimeline.mark("prep");
        ReplyTimeline.llm("deepseek", 4100);
        ReplyTimeline.span("tool:search_products", 310);
        ReplyTimeline.llm("deepseek", 3900);
        ReplyTimeline.span("review", 1200);
        ReplyTimeline.mark("flow");
        ReplyTimeline.finish("WEB", "c1");
        String t = ReplyTimeline.takeLast();
        assertThat(t).startsWith("total=").contains("prep=");
        assertThat(t).contains("flow=").contains("(llm1:deepseek=4100 tool:search_products=310 llm2:deepseek=3900 review=1200)").contains("post=");
        assertThat(ReplyTimeline.takeLast()).as("read once").isNull();
    }

    @Test
    void outsideAReplyEverythingIsANoOpAndNeverThrows() {
        ReplyTimeline.mark("x");
        ReplyTimeline.llm("m", 1);
        ReplyTimeline.span("s", 1);
        ReplyTimeline.finish("WEB", "none");
        assertThat(ReplyTimeline.takeLast()).isNull();
    }

    @Test
    void aFinishedReplyLeavesNothingBehindForTheNextOneOnTheSameThread() {
        ReplyTimeline.begin();
        ReplyTimeline.llm("a", 5);
        ReplyTimeline.finish("WEB", "c1");
        ReplyTimeline.begin();
        ReplyTimeline.mark("prep");
        ReplyTimeline.finish("WEB", "c2");
        assertThat(ReplyTimeline.takeLast()).doesNotContain("llm1:a");
    }

    @Test
    void manyRepliesAtTheSameTimeEachKeepTheirOwnTimeline() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> got = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int k = i;
            got.add(pool.submit(() -> {
                ReplyTimeline.begin();
                for (int j = 0; j <= k; j++) ReplyTimeline.llm("m" + k, 100 + j);
                Thread.sleep(20);
                ReplyTimeline.mark("flow");
                ReplyTimeline.finish("WEB", "c" + k);
                return ReplyTimeline.takeLast();
            }));
        }
        for (int i = 0; i < 8; i++) {
            String t = got.get(i).get(10, TimeUnit.SECONDS);
            assertThat(t).as("reply " + i).contains("llm" + (i + 1) + ":m" + i).doesNotContain("llm" + (i + 2) + ":");
            for (int other = 0; other < 8; other++) if (other != i) assertThat(t).doesNotContain(":m" + other + "=");
        }
        pool.shutdownNow();
    }
}

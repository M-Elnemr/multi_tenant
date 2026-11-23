package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConversationGateTest {
    final ExecutorService pool = Executors.newFixedThreadPool(8);
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());   // what went out: every line the reply covered, joined with |

    @AfterEach
    void stop() { pool.shutdownNow(); }

    static String all(String carried, String joined) { return carried == null ? joined : carried + "|" + joined; }

    @Test
    void aLineDuringGenerationGivesExactlyOneFinalReplyCoveringAllLines() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, carried) -> {
            started.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }
            if (ConversationGate.shouldAbort()) { ConversationGate.markDiscarded(); return; }   // what ChatService does right before saving
            sent.add(all(carried, j));
        }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.busy("c")).isTrue();
        long t0 = System.nanoTime();
        gate.run("c", "B", List.of(), (j, m, carried) -> sent.add(all(carried, j)));   // returns at once: the worker is not held while the first reply finishes
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
        gate.run("c", "C", List.of(), (j, m, carried) -> sent.add(all(carried, j)));   // two waiting bursts are one
        proceed.countDown();
        for (int i = 0; i < 100 && gate.busy("c"); i++) Thread.sleep(20);
        assertThat(sent).containsExactly("A|B\nC");
        assertThat(gate.busy("c")).isFalse();
    }

    @Test
    void aLineAfterTheReplyWasSentGetsItsOwnSecondReply() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, carried) -> {
            started.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }
            sent.add(all(carried, j));   // the reply is already out (or cannot be dropped, e.g. an order was placed): it is never discarded
        }));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        gate.run("c", "B", List.of(), (j, m, carried) -> sent.add(all(carried, j)));
        proceed.countDown();
        for (int i = 0; i < 100 && gate.busy("c"); i++) Thread.sleep(20);
        assertThat(sent).containsExactly("A", "B");
    }

    @Test
    void differentConversationsRunTogether() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch both = new CountDownLatch(2);
        for (String k : List.of("x", "y")) pool.execute(() -> gate.run(k, "line", List.of(), (j, m, c) -> { both.countDown(); try { both.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } sent.add(k); }));
        for (int i = 0; i < 100 && sent.size() < 2; i++) Thread.sleep(20);
        assertThat(sent).containsExactlyInAnyOrder("x", "y");
        assertThat(both.getCount()).isZero();
    }

    @Test
    void offRunsEveryBurstAtOnceAsBefore() throws Exception {
        ConversationGate gate = new ConversationGate(false);
        CountDownLatch inside = new CountDownLatch(1), second = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, c) -> { inside.countDown(); try { second.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } sent.add("A"); }));
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();
        gate.run("c", "B", List.of(), (j, m, c) -> { sent.add("B"); second.countDown(); });
        for (int i = 0; i < 100 && sent.size() < 2; i++) Thread.sleep(20);
        assertThat(sent).containsExactly("B", "A");
        assertThat(gate.busy("c")).isFalse();
    }

    @Test
    void underConcurrencyNoLineIsLostOrAnsweredTwice() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        int n = 120;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            String line = "L" + i;
            pool.execute(() -> {
                try { go.await(); } catch (InterruptedException ignored) { }
                gate.run("c", line, List.of(), (j, m, carried) -> {
                    try { Thread.sleep(3); } catch (InterruptedException ignored) { }
                    // like ChatService: a superseded reply that can be dropped is dropped; some replies (an order was placed) cannot be, and go out
                    if (ConversationGate.shouldAbort() && !j.contains("L7")) { ConversationGate.markDiscarded(); return; }
                    sent.add(all(carried, j));
                });
                done.countDown();
            });
        }
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 300 && gate.busy("c"); i++) Thread.sleep(20);
        assertThat(gate.busy("c")).isFalse();
        List<String> lines = new ArrayList<>();
        for (String s : sent) for (String part : s.split("[|\n]")) lines.add(part);
        assertThat(lines).as("every customer line is answered exactly once").hasSize(n).doesNotHaveDuplicates();
        assertThat(sent.size()).as("far fewer replies than lines").isLessThan(n);
        assertThat(Arrays.asList(sent.get(sent.size() - 1).split("[|\n]"))).as("the last reply is the one that covers the latest lines").isNotEmpty();
    }

    @Test
    void theFillerGoesOutOnceAMinute() {
        ConversationGate gate = new ConversationGate(true);
        assertThat(gate.fillerAllowed("k")).isTrue();
        assertThat(gate.fillerAllowed("k")).isFalse();
        assertThat(gate.fillerAllowed("other")).isTrue();
    }
}

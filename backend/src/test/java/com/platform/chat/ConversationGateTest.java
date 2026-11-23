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

    /** What ChatService does right before saving and sending: the reply may go out only while no newer burst took the conversation over. */
    static boolean mayGoOut() {
        if (ConversationGate.tryCommit()) return true;
        ConversationGate.markDiscarded();
        return false;
    }

    @Test
    void aNewerLineStartsAtOnceWithoutWaitingForTheSlowCallAndOnlyTheLastReplyGoesOut() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch startedA = new CountDownLatch(1), startedB = new CountDownLatch(1), startedC = new CountDownLatch(1), proceed = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, carried) -> {
            startedA.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }   // the slow model call of the first reply
            if (mayGoOut()) sent.add(all(carried, j));
        }));
        assertThat(startedA.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.busy("c")).isTrue();
        pool.execute(() -> gate.run("c", "B", List.of(), (j, m, carried) -> {
            startedB.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }
            if (mayGoOut()) sent.add(all(carried, j));
        }));
        assertThat(startedB.await(1, TimeUnit.SECONDS)).as("B starts at once, while A's slow call is still running").isTrue();
        pool.execute(() -> gate.run("c", "C", List.of(), (j, m, carried) -> {
            startedC.countDown();
            if (mayGoOut()) sent.add(all(carried, j));   // the latest line: nothing newer arrives
        }));
        assertThat(startedC.await(1, TimeUnit.SECONDS)).as("C starts at once too").isTrue();
        for (int i = 0; i < 100 && sent.isEmpty(); i++) Thread.sleep(20);
        assertThat(sent).as("C answers A, B and C together, without waiting for the older calls").containsExactly("A\nB|C");
        proceed.countDown();   // the older calls finish later: their replies are dropped
        for (int i = 0; i < 100 && gate.busy("c"); i++) Thread.sleep(20);
        Thread.sleep(150);
        assertThat(sent).as("exactly one reply went out").containsExactly("A\nB|C");
        assertThat(gate.busy("c")).isFalse();
    }

    @Test
    void aReplyThatIsAlreadyGoingOutIsNeverDroppedAndTheNewLineWaitsForIt() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch committed = new CountDownLatch(1), proceed = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, carried) -> {
            assertThat(ConversationGate.tryCommit()).isTrue();   // the reply is about to be saved and sent
            committed.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }
            sent.add(all(carried, j));
        }));
        assertThat(committed.await(2, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        gate.run("c", "B", List.of(), (j, m, carried) -> sent.add(all(carried, j)));   // parked: returns at once, runs after A
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
        assertThat(sent).isEmpty();
        proceed.countDown();
        for (int i = 0; i < 100 && gate.busy("c"); i++) Thread.sleep(20);
        assertThat(sent).containsExactly("A", "B");
    }

    @Test
    void aReplyThatChangedSomethingIsMarkedCommittedAndTheNewLineWaits() throws Exception {
        ConversationGate gate = new ConversationGate(true);
        CountDownLatch placed = new CountDownLatch(1), proceed = new CountDownLatch(1);
        pool.execute(() -> gate.run("c", "A", List.of(), (j, m, carried) -> {
            ConversationGate.markCommitted();   // an order was placed: this reply must go out
            placed.countDown();
            try { proceed.await(); } catch (InterruptedException ignored) { }
            sent.add(all(carried, j));
        }));
        assertThat(placed.await(2, TimeUnit.SECONDS)).isTrue();
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
                    // like ChatService: a superseded reply is dropped, the one that still owns the conversation goes out
                    if (!mayGoOut()) return;
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

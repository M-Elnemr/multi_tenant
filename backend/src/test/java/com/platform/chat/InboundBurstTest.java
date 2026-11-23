package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InboundBurstTest {
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    final List<String> fired = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { timers.shutdownNow(); }

    @Test
    void quickLinesAreJoinedAndAnsweredOnce() throws Exception {
        InboundBurst b = new InboundBurst(timers, Runnable::run, 150, 2000);
        b.add("c1", "hi", fired::add);
        b.add("c1", "do you have the black dress", fired::add);
        b.add("c2", "other customer", fired::add);
        b.add("c1", "size M", fired::add);
        Thread.sleep(500);
        assertThat(fired).containsExactlyInAnyOrder("hi\ndo you have the black dress\nsize M", "other customer");
    }

    @Test
    void noWaitingWindowStartsEachLineAtOnceOnTheWorker() throws Exception {   // the owner's setting: CHAT_BURST_MS=0, a timer-and-worker burst with a zero window
        var worker = Executors.newFixedThreadPool(2);
        try {
            InboundBurst b = new InboundBurst(timers, worker, 0, 15_000);
            long start = System.nanoTime();
            b.add("c1", "السلام عليكم", fired::add);
            for (int i = 0; i < 40 && fired.isEmpty(); i++) Thread.sleep(10);
            assertThat(fired).as("answered without any quiet window").containsExactly("السلام عليكم");
            assertThat((System.nanoTime() - start) / 1_000_000).as("no waiting: well under a second").isLessThan(400);
            b.add("c1", "عايز جاكيت", fired::add);   // a later line is its own turn (the conversation gate decides what to do with an answer in progress)
            for (int i = 0; i < 40 && fired.size() < 2; i++) Thread.sleep(10);
            assertThat(fired).containsExactly("السلام عليكم", "عايز جاكيت");
        } finally { worker.shutdownNow(); }
    }

    @Test
    void aSlowTyperIsNotHeldPastTheCeiling() throws Exception {
        InboundBurst b = new InboundBurst(timers, Runnable::run, 200, 300);
        b.add("c", "one", fired::add);
        Thread.sleep(150);
        b.add("c", "two", fired::add);   // restarts the quiet window, but only up to 300 ms after the first line
        Thread.sleep(250);
        assertThat(fired).containsExactly("one\ntwo");
        b.add("c", "later", fired::add);
        Thread.sleep(400);
        assertThat(fired).containsExactly("one\ntwo", "later");
    }

    @Test
    void aPhotoBeingReadHoldsTheBurstAndKeepsItsPlace() throws Exception {
        InboundBurst b = new InboundBurst(timers, Runnable::run, 100, 300);
        var photo = b.hold("c", fired::add);
        b.add("c", "بكام ده؟", fired::add);
        Thread.sleep(500);   // past the window and the ceiling: still waiting for the photo
        assertThat(fired).isEmpty();
        photo.accept("[صورة من العميل: فستان وردي]");
        Thread.sleep(50);
        assertThat(fired).containsExactly("[صورة من العميل: فستان وردي]\nبكام ده؟");
        var unreadable = b.hold("d", fired::add);
        unreadable.accept(null);
        unreadable.accept("twice is ignored");
        Thread.sleep(250);
        assertThat(fired).containsExactly("[صورة من العميل: فستان وردي]\nبكام ده؟", "");
    }

    @Test
    void offMeansEveryLineGoesStraightOn() {
        assertThat(new InboundBurst(timers, Runnable::run, 0, 0).enabled()).isFalse();
    }

    @Test
    void typingExtendsTheQuietWindowButNeverPastTheCeiling() throws Exception {
        InboundBurst b = new InboundBurst(timers, Runnable::run, 200, 500);
        b.add("c", "one", fired::add);
        Thread.sleep(120);
        b.touch("c");   // the customer is still typing: the window starts again
        Thread.sleep(120);
        b.touch("c");
        Thread.sleep(120);
        assertThat(fired).as("still waiting, the window keeps restarting").isEmpty();
        for (int i = 0; i < 6; i++) { b.touch("c"); Thread.sleep(100); }   // typing forever: the ceiling (500 ms after the first line) answers anyway
        assertThat(fired).containsExactly("one");
        b.touch("nobody");   // nothing waiting: nothing happens
    }

    @Test
    void waitingIsTrueFromTheFirstLineUntilTheHandlerHasFinished() throws Exception {
        java.util.concurrent.CountDownLatch inHandler = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        InboundBurst b = new InboundBurst(timers, java.util.concurrent.Executors.newSingleThreadExecutor(), 100, 1000);
        assertThat(b.waiting("c")).isFalse();
        b.add("c", "hi", t -> { inHandler.countDown(); try { release.await(); } catch (InterruptedException ignored) { } fired.add(t); });
        assertThat(b.waiting("c")).as("waiting for the quiet window").isTrue();
        assertThat(inHandler.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(b.waiting("c")).as("flushed but the reply is still being written").isTrue();
        release.countDown();
        for (int i = 0; i < 50 && b.waiting("c"); i++) Thread.sleep(20);
        assertThat(b.waiting("c")).isFalse();
        assertThat(fired).containsExactly("hi");
    }

    @Test
    void theCeilingIsConfigurable() throws Exception {
        InboundBurst b = new InboundBurst(timers, Runnable::run, 300, 100);   // a ceiling below the window never makes the window shorter than asked for
        b.add("c", "x", fired::add);
        Thread.sleep(500);
        assertThat(fired).containsExactly("x");
    }
}

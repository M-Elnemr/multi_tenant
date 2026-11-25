package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** «Typing…» shows only for a reply that takes a while, is repeated while it is written, and stops with the reply. */
class TypingTest {
    @AfterEach
    void back() { new Typing(3_000); }

    @Test
    void aQuickReplyShowsNoTyping() throws Exception {
        new Typing(300);
        AtomicInteger sent = new AtomicInteger();
        try (var t = Typing.start(sent::incrementAndGet, 100)) { Thread.sleep(50); }
        Thread.sleep(500);
        assertThat(sent.get()).isZero();
    }

    @Test
    void aSlowReplyShowsTypingAfterTheDelayAgainWhileItIsWrittenAndNoMoreAfterIt() throws Exception {
        new Typing(100);
        AtomicInteger sent = new AtomicInteger();
        try (var t = Typing.start(sent::incrementAndGet, 100)) {
            Thread.sleep(60);
            assertThat(sent.get()).as("not before the delay").isZero();
            Thread.sleep(400);
        }
        int atReply = sent.get();
        assertThat(atReply).as("shown and repeated").isGreaterThanOrEqualTo(2);
        Thread.sleep(400);
        assertThat(sent.get()).as("stopped with the reply").isEqualTo(atReply);
    }

    @Test
    void aSignalTheNetworkRefusesNeverReachesTheReplyAndANetworkWithoutTypingSendsNothing() throws Exception {
        new Typing(50);
        try (var t = Typing.start(() -> { throw new IllegalStateException("refused"); }, 50)) { Thread.sleep(200); }
        try (var t = Typing.start(null, 50)) { Thread.sleep(10); }
        AtomicInteger sent = new AtomicInteger();
        try (var t = Typing.start(sent::incrementAndGet, 0)) { Thread.sleep(200); }
        assertThat(sent.get()).isZero();
    }
}

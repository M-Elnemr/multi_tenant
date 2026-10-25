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
    void offMeansEveryLineGoesStraightOn() {
        assertThat(new InboundBurst(timers, Runnable::run, 0, 0).enabled()).isFalse();
    }
}

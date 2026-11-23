package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The health breaker (DR-R): opens after two real failures in a minute, probes once per 30 s, closes when the probe answers in time. A fake clock, no sleeping. */
class ProviderHealthTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final ProviderHealth h = new ProviderHealth(now::get);

    @Test
    void opensAfterTwoTimeoutsInAMinuteAndNotAfterOne() {
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.allow("glm")).as("one failure is not an outage").isTrue();
        now.addAndGet(10_000);
        h.failure("glm", ProviderFailure.SERVER_ERROR, 300);
        assertThat(h.isOpen("glm")).isTrue();
        assertThat(h.allow("glm")).as("open: new replies skip the primary").isFalse();
        assertThat(h.allow("qwen")).as("the other model is untouched").isTrue();
    }

    @Test
    void rateLimitAndConnectionFailuresCountButAnEmptyAnswerDoesNot() {
        h.failure("glm", ProviderFailure.EMPTY, 500);
        h.failure("glm", ProviderFailure.MALFORMED, 500);
        h.failure("glm", ProviderFailure.OVERLOAD_MESSAGE, 500);
        assertThat(h.isOpen("glm")).isFalse();
        h.failure("glm", ProviderFailure.RATE_LIMIT, 100);
        h.failure("glm", ProviderFailure.CONNECTION, 100);
        assertThat(h.isOpen("glm")).isTrue();
    }

    @Test
    void failuresOlderThanAMinuteAreForgotten() {
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        now.addAndGet(ProviderHealth.WINDOW_MS + 1);
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.isOpen("glm")).isFalse();
    }

    @Test
    void halfOpenLetsOneRequestThroughEvery30SecondsAndClosesWhenItAnswersInTime() {
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.allow("glm")).isFalse();
        now.addAndGet(29_000);
        assertThat(h.allow("glm")).as("not yet 30 s").isFalse();
        now.addAndGet(1_500);
        assertThat(h.allow("glm")).as("the probe").isTrue();
        assertThat(h.allow("glm")).as("only one probe per 30 s").isFalse();
        h.success("glm", 2_000);
        assertThat(h.isOpen("glm")).isFalse();
        assertThat(h.allow("glm")).isTrue();
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.isOpen("glm")).as("the window started afresh: one failure does not reopen").isFalse();
    }

    @Test
    void aFailedProbeKeepsItOpenAndThenProbesAgain30SecondsLater() {
        h.failure("glm", ProviderFailure.SERVER_ERROR, 100);
        h.failure("glm", ProviderFailure.SERVER_ERROR, 100);
        now.addAndGet(31_000);
        assertThat(h.allow("glm")).isTrue();
        h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.isOpen("glm")).isTrue();
        now.addAndGet(20_000);
        assertThat(h.allow("glm")).as("30 s count from the failed probe").isFalse();
        now.addAndGet(11_000);
        assertThat(h.allow("glm")).isTrue();
    }

    @Test
    void aMedianCallTimeOver20SecondsOpensItAndASlowProbeDoesNotCloseIt() {
        h.success("glm", 25_000);
        h.success("glm", 26_000);
        assertThat(h.isOpen("glm")).as("two samples are not enough").isFalse();
        h.success("glm", 27_000);
        assertThat(h.isOpen("glm")).isTrue();
        now.addAndGet(31_000);
        assertThat(h.allow("glm")).isTrue();
        h.success("glm", 24_000);
        assertThat(h.isOpen("glm")).as("it answered, but too slowly to be healthy").isTrue();
        now.addAndGet(31_000);
        assertThat(h.allow("glm")).isTrue();
        h.success("glm", 3_000);
        assertThat(h.isOpen("glm")).isFalse();
    }

    @Test
    void fastCallsWithOneSlowOneNeverOpenIt() {
        h.success("glm", 3_000); h.success("glm", 4_000); h.success("glm", 31_000); h.success("glm", 5_000);
        assertThat(h.isOpen("glm")).isFalse();
    }

    @Test
    void switchedOffItNeverBlocks() {
        h.enabled(false);
        for (int i = 0; i < 5; i++) h.failure("glm", ProviderFailure.TIMEOUT, 20_000);
        assertThat(h.allow("glm")).isTrue();
        assertThat(h.isOpen("glm")).isFalse();
    }
}

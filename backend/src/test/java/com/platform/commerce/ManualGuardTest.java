package com.platform.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.unofficial.HumanPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The audience limits of the manual-style route: how old, how many messages exchanged, not messaged by this route within 30 days. */
class ManualGuardTest {
    final HumanPolicy.Profile p = HumanPolicy.manualDefaults();
    final Instant now = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void audienceLimits() {
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(10)), 3, 2, false, now)).isNull();
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(179)), 2, 1, false, now)).isNull();
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(181)), 3, 2, false, now)).isEqualTo("MANUAL_TOO_OLD");
        assertThat(SegmentService.manualGuard(p, null, 3, 2, false, now)).isEqualTo("MANUAL_TOO_OLD");
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(2)), 1, 1, false, now)).isEqualTo("MANUAL_FEW_MESSAGES");     // fewer than 2 messages exchanged
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(2)), 3, 0, false, now)).isEqualTo("MANUAL_FEW_MESSAGES");     // the customer never wrote
        assertThat(SegmentService.manualGuard(p, now.minus(Duration.ofDays(2)), 3, 2, true, now)).isEqualTo("MANUAL_RECENT");             // messaged by this route within 30 days
    }
}

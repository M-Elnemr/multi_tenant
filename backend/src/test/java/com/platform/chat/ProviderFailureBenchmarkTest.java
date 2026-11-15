package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The failure check runs on every answer: it must cost microseconds, not milliseconds. Prints with BENCH_OVERHEAD=1. */
class ProviderFailureBenchmarkTest {
    @Test
    void theCheckCostsMicrosecondsOnTypicalAndWorstCaseAnswers() {
        String typical = "تمام يا فندم، التيشيرت القطن الأسود مقاس L متاح بـ ٢٥٠ جنيه، تحب أكمل الأوردر؟";
        String worst = "تمام يا فندم، القميص القطن متاح بكل المقاسات والألوان وبيوصلك خلال يومين. ".repeat(5).substring(0, 340);   // a long ordinary answer: the check scans all of it and finds nothing
        for (int i = 0; i < 20_000; i++) { ProviderFailure.overloadMessage(typical); ProviderFailure.overloadMessage(worst); }   // warm up
        int n = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ProviderFailure.overloadMessage(typical);
        double typicalUs = (System.nanoTime() - t0) / 1000.0 / n;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ProviderFailure.overloadMessage(worst);
        double worstUs = (System.nanoTime() - t0) / 1000.0 / n;
        if (System.getenv("BENCH_OVERHEAD") != null) System.out.printf("PROVIDER_FAILURE_CHECK typical=%.1f us worst-case(340 chars)=%.1f us%n", typicalUs, worstUs);
        assertThat(typicalUs).as("typical answer, microseconds").isLessThan(500);
        assertThat(worstUs).as("worst case, microseconds").isLessThan(2000);
    }
}

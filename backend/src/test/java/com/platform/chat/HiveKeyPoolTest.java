package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hive allows about 5 requests a second per key: the pool lends the enabled keys in turn, rests a key that is refused or overloaded, and never shows a key in full. */
class HiveKeyPoolTest {
    @Test
    void keysAreLentInTurn() {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222", "key-cccc-3333");
        assertThat(pool.size()).isEqualTo(3);
        List<String> got = new ArrayList<>();
        for (int i = 0; i < 6; i++) got.add(pool.acquire(null).key());
        assertThat(got.subList(0, 3)).containsExactlyInAnyOrder("key-aaaa-1111", "key-bbbb-2222", "key-cccc-3333");
        assertThat(got.subList(3, 6)).containsExactlyElementsOf(got.subList(0, 3));   // then the same order again: an even spread
    }

    @Test
    void aKeyThatAnswers429RestsAndTheOthersTakeItsRequests() {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222");
        var a = pool.acquire(null);
        pool.failed(a, 429);
        for (int i = 0; i < 6; i++) assertThat(pool.acquire(null).key()).isNotEqualTo(a.key());
        assertThat(pool.view().stream().filter(m -> m.get("id").equals(a.id())).findFirst().orElseThrow().get("restingSeconds")).isNotEqualTo(0L);
    }

    @Test
    void aRefusedKeyRestsLongerThanABusyOne() {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222");
        var a = pool.acquire(null);
        var b = pool.acquire(null);
        pool.failed(a, 429);
        pool.failed(b, 401);
        long busy = (Long) pool.view().stream().filter(m -> m.get("id").equals(a.id())).findFirst().orElseThrow().get("restingSeconds");
        long refused = (Long) pool.view().stream().filter(m -> m.get("id").equals(b.id())).findFirst().orElseThrow().get("restingSeconds");
        assertThat(refused).isGreaterThan(busy);
    }

    @Test
    void whenEveryKeyIsRestingARequestStillGetsOneAndTheServiceDecides() {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222");
        pool.failed(pool.acquire(null), 429);
        pool.failed(pool.acquire(null), 429);
        assertThat(pool.acquire(null)).isNotNull();
    }

    @Test
    void aTwinOrRetryAvoidsTheKeyTheFirstTryUsedWhenAnotherIsFree() {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222");
        for (int i = 0; i < 10; i++) {
            var first = pool.acquire(null);
            assertThat(pool.acquire(first.id()).id()).isNotEqualTo(first.id());
        }
        var single = HiveKeyPool.of("only-key-1234");
        var l = single.acquire(null);
        assertThat(single.acquire(l.id()).key()).as("one key only: the same one").isEqualTo("only-key-1234");
    }

    @Test
    void noKeyMeansNotConfigured() {
        var pool = HiveKeyPool.of();
        assertThat(pool.hasKeys()).isFalse();
        assertThat(pool.acquire(null)).isNull();
    }

    @Test
    void theViewShowsOnlyTheLastFourCharacters() {
        var pool = HiveKeyPool.of("secret-key-value-9876");
        assertThat(pool.view().toString()).contains("9876").doesNotContain("secret-key-value");
    }

    @Test
    void aRestingKeyComesBackIntoTheRotationWhenItsRestIsOver() throws Exception {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222");
        pool.restSeconds = 1;
        var a = pool.acquire(null);
        pool.failed(a, 429);
        for (int i = 0; i < 4; i++) assertThat(pool.acquire(null).key()).isNotEqualTo(a.key());
        Thread.sleep(1_200);
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 4; i++) seen.add(pool.acquire(null).key());
        assertThat(seen).as("recovered: both keys are lent again").containsExactlyInAnyOrder("key-aaaa-1111", "key-bbbb-2222");
        assertThat((Long) pool.view().stream().filter(m -> m.get("id").equals(a.id())).findFirst().orElseThrow().get("restingSeconds")).isZero();
    }

    @Test
    void manyThreadsGetAnEvenSpreadAndOneKeyIsNeverLentToEveryone() throws Exception {
        var pool = HiveKeyPool.of("key-aaaa-1111", "key-bbbb-2222", "key-cccc-3333", "key-dddd-4444");
        var counts = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
        var ex = java.util.concurrent.Executors.newFixedThreadPool(16);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < 16; t++) futures.add(ex.submit(() -> { for (int i = 0; i < 500; i++) counts.computeIfAbsent(pool.acquire(null).key(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet(); }));
        for (var f : futures) f.get();
        ex.shutdown();
        assertThat(counts.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum()).isEqualTo(8_000);
        assertThat(counts).hasSize(4);
        counts.values().forEach(c -> assertThat(c.get()).as("each key gets 2,000 of 8,000").isEqualTo(2_000));
    }
}

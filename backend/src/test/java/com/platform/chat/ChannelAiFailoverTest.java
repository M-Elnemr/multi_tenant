package com.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.chat.AiProvider.AiUnavailableException;
import com.platform.chat.AiProvider.ToolCall;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.AiProvider.Usage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The early cross-model failover and the health breaker (DR-R) with scripted providers: one that hangs, one that answers 500 or 429, one that answers nothing. Same thread-per-call behaviour as production,
 * with the failover time shortened to 300 ms so the tests are quick.
 */
class ChannelAiFailoverTest {
    /** A model that waits {@code delayMs}, then does {@code outcome}. Counts its calls; thread-safe. */
    static class Fake implements AiProvider {
        final String code;
        volatile long delayMs;
        volatile Supplier<Turn> outcome;
        final AtomicInteger calls = new AtomicInteger();
        Fake(String code, long delayMs, Supplier<Turn> outcome) { this.code = code; this.delayMs = delayMs; this.outcome = outcome; }
        @Override public String code() { return code; }
        @Override public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
            calls.incrementAndGet();
            try { if (delayMs > 0) Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AiUnavailableException("interrupted"); }
            return outcome.get();
        }
    }

    static Turn text(String t) { return new Turn("model", t, List.of(), List.of(), null, new Usage(1000, 50, 0)); }
    static Supplier<Turn> says(String t) { return () -> text(t); }
    static Supplier<Turn> fails(String category) { return () -> { throw new AiUnavailableException(category, "scripted " + category, null); }; }

    static final List<Turn> ASK = List.of(Turn.user("بكام الجاكيت؟"));
    static final List<ToolDef> TOOLS = List.of();

    Fake glm, qwen;
    ChannelAi ai;
    final UUID tenant = UUID.randomUUID();
    final List<String> billed = new CopyOnWriteArrayList<>();
    OpenAiProvider.LostTwinSink before;

    @BeforeEach
    void setUp() {
        glm = new Fake("glm", 0, says("from glm"));
        qwen = new Fake("qwen", 0, says("from qwen"));
        ai = new ChannelAi(glm, new ChannelAiTest.Scripted("anthropic", false), glm, qwen, "default", "default");
        ai.failoverMs(300);
        before = OpenAiProvider.LOST_TWIN_SINK;
        OpenAiProvider.LOST_TWIN_SINK = (t, code, usage) -> { if (tenant.equals(t)) billed.add(code + ":" + usage.input()); };
        AiBudget.start(45_000);
        AiBudget.forTenant(tenant);
    }

    @AfterEach
    void tearDown() { OpenAiProvider.LOST_TWIN_SINK = before; AiBudget.clear(); }

    ChannelAi.Routed route() { return ai.forChannel("WEB"); }

    static void waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
    }

    @Test
    void aPrimaryThatAnswersInTimeNeverTouchesTheBackup() {
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).isEqualTo("from glm");
        assertThat(qwen.calls.get()).isZero();
        assertThat(r.fellBack()).isFalse();
    }

    @Test
    void aHangingPrimaryIsAnsweredByTheBackupAfterTheFailoverTimeNotAfterTheWholeTimeout() throws Exception {
        glm.delayMs = 4_000;
        var r = route();
        long t0 = System.nanoTime();
        Turn t = r.next("s", ASK, TOOLS);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(t.text()).isEqualTo("from qwen");
        assertThat(ms).as("about the failover time plus the backup's own time").isBetween(250L, 1_500L);
        assertThat(r.fellBack()).isTrue();
        assertThat(r.code()).isEqualTo("qwen");
        assertThat(glm.calls.get()).isEqualTo(1);
        assertThat(qwen.calls.get()).isEqualTo(1);
    }

    @Test
    void theSlowLoserIsNotDoubleChargedItsTokensAreRecordedOnceAgainstTheShopWhenItEnds() throws Exception {
        glm.delayMs = 1_200;   // answers after the backup has already won
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
        assertThat(billed).as("the winner is billed by the reply's own rounds, not here").isEmpty();
        waitFor(() -> !billed.isEmpty(), 4_000);
        assertThat(billed).containsExactly("glm:1000");   // exactly the loser, exactly once
        Thread.sleep(300);
        assertThat(billed).hasSize(1);
    }

    @Test
    void theBackupIsTheLoserWhenThePrimaryAnswersJustAfterTheFailoverTime() throws Exception {
        glm.delayMs = 500;
        qwen.delayMs = 1_500;
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).isEqualTo("from glm");
        assertThat(r.fellBack()).as("the primary won: the reply stays on it").isFalse();
        assertThat(r.code()).isEqualTo("glm");
        waitFor(() -> !billed.isEmpty(), 4_000);
        assertThat(billed).containsExactly("qwen:1000");
    }

    @Test
    void anImmediateServerErrorOr429GoesToTheBackupWithoutWaitingTheFailoverTime() {
        for (String category : List.of(ProviderFailure.SERVER_ERROR, ProviderFailure.RATE_LIMIT)) {
            glm.outcome = fails(category);
            var r = route();
            long t0 = System.nanoTime();
            assertThat(r.next("s", ASK, TOOLS).text()).as(category).isEqualTo("from qwen");
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(250);
        }
    }

    @Test
    void anEmptyAnswerFromThePrimaryGoesToTheBackup() {
        glm.outcome = says("");
        assertThat(route().next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
    }

    @Test
    void anEmptyAnswerFromTheFasterModelDoesNotWinTheRace() {
        glm.delayMs = 700;
        qwen.outcome = says("");   // the backup answers nothing at 300 ms; the primary's real answer at 700 ms must win
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).isEqualTo("from glm");
        assertThat(r.fellBack()).isFalse();
    }

    @Test
    void whenBothFailTheBackupIsAskedOnceAndTheCallerGetsTheFailure() {
        glm.delayMs = 600;
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        qwen.outcome = fails(ProviderFailure.SERVER_ERROR);
        var r = route();
        assertThatThrownBy(() -> r.next("s", ASK, TOOLS)).isInstanceOf(AiUnavailableException.class);
        assertThat(qwen.calls.get()).as("not asked again after the race").isEqualTo(1);
        assertThat(glm.calls.get()).isEqualTo(1);
        assertThat(r.restartOnFallback()).as("nothing is left to restart on: the turn is deferred instead").isNull();
    }

    @Test
    void bothHangingEndsWithinTheReplyBudgetNotAfterTheTimeout() {
        AiBudget.start(2_500);
        glm.delayMs = 6_000;
        qwen.delayMs = 6_000;
        var r = route();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> r.next("s", ASK, TOOLS)).isInstanceOf(AiUnavailableException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(3_000);
    }

    @Test
    void noFailoverAfterAToolExchangeHasBegun() {
        glm.delayMs = 700;
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        var r = route();
        List<Turn> mid = List.of(Turn.user("x"), new Turn("model", "", List.of(new ToolCall("place_order", Map.of())), List.of(), null), Turn.tool(List.of()));
        assertThatThrownBy(() -> r.next("s", mid, TOOLS)).isInstanceOf(AiUnavailableException.class);
        assertThat(qwen.calls.get()).as("a model's own tool exchange is never replayed to the other model").isZero();
        assertThat(r.fellBack()).isFalse();
    }

    @Test
    void failoverOffMeansTheOldSequentialFallback() {
        ai.failoverMs(0);
        glm.delayMs = 700;
        long t0 = System.nanoTime();
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).as("no race: the primary is waited for").isEqualTo("from glm");
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(650);
        assertThat(qwen.calls.get()).isZero();
    }

    // ---- the breaker through ChannelAi -------------------------------------------------------------------------------------------

    @Test
    void twoTimeoutsOpenTheBreakerAndTheNextRepliesGoStraightToTheBackup() {
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        for (int i = 0; i < 2; i++) assertThat(route().next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
        assertThat(glm.calls.get()).isEqualTo(2);
        assertThat(ai.health.isOpen("glm")).isTrue();
        glm.outcome = says("from glm");   // even recovered, the breaker skips it until the probe
        var r = route();
        assertThat(r.next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
        assertThat(glm.calls.get()).as("the primary was not asked at all").isEqualTo(2);
        assertThat(r.fellBack()).isTrue();
    }

    @Test
    void anEmptyAnswerDoesNotCountAgainstTheProvider() {
        glm.outcome = says("");
        for (int i = 0; i < 4; i++) assertThat(route().next("s", ASK, TOOLS).text()).isEqualTo("from qwen");
        assertThat(ai.health.isOpen("glm")).isFalse();
        assertThat(glm.calls.get()).isEqualTo(4);
    }

    @Test
    void breakerOffAlwaysAsksThePrimary() {
        ai.breakerEnabled(false);
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        for (int i = 0; i < 4; i++) route().next("s", ASK, TOOLS);
        assertThat(glm.calls.get()).isEqualTo(4);
    }

    @Test
    void aReviewCallThatTimesOutIsNotHeldAgainstTheProvider() {
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        var r = route();
        AiBudget.capCalls(3_500);
        for (int i = 0; i < 5; i++) { try { r.next("review", List.of(Turn.user("x"), Turn.model("d"), Turn.tool(List.of())), TOOLS); } catch (AiUnavailableException expected) { /* a later round: no backup */ } }
        AiBudget.uncapCalls();
        assertThat(ai.health.isOpen("glm")).isFalse();
    }

    @Test
    void withNoBackupTheProviderIsStillAskedWhateverTheBreakerSays() {
        ChannelAi alone = new ChannelAi(glm, new ChannelAiTest.Scripted("anthropic", false), glm, null, "default", "default");
        glm.outcome = fails(ProviderFailure.TIMEOUT);
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> alone.forChannel("WEB").next("s", ASK, TOOLS)).isInstanceOf(AiUnavailableException.class);
        assertThat(glm.calls.get()).isEqualTo(4);
    }
}

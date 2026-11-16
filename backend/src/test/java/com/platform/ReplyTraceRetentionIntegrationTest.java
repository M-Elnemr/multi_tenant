package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.chat.ReplyTraceStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The 60-day cleanup of chat.reply_traces: it keeps recent traces, deletes old ones in bounded batches, and a failure only ends the run. */
class ReplyTraceRetentionIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ReplyTraceStore store;

    UUID shop() throws Exception {
        Tenant t = onboard("STORE");
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    void row(UUID tenant, String ageDays) {
        jdbc.sql("INSERT INTO chat.reply_traces (trace_id, tenant_id, started_at, outcome, summary, events) VALUES (gen_random_uuid(), :t, now() - CAST(:a AS interval), 'completed', '{}', '[]')").param("t", tenant).param("a", ageDays + " days").update();
    }

    long count(UUID tenant) { return jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t").param("t", tenant).query(Long.class).single(); }

    @Test
    void tracesOlderThanSixtyDaysAreDeletedAndRecentOnesKept() throws Exception {
        UUID t = shop();
        row(t, "200"); row(t, "61"); row(t, "59"); row(t, "1"); row(t, "0");
        store.purge(60, 5_000, 40);
        assertThat(count(t)).isEqualTo(3L);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.reply_traces WHERE tenant_id = :t AND started_at < now() - interval '60 days'").param("t", t).query(Long.class).single()).isZero();
    }

    @Test
    void aRunIsBoundedSoABigBacklogIsTrimmedOverSeveralRunsNotInOneLongStatement() throws Exception {
        UUID t = shop();
        for (int i = 0; i < 9; i++) row(t, "100");
        row(t, "5");
        assertThat(store.purge(60, 2, 3)).isEqualTo(6L);   // 3 batches of 2 and no more in one run
        assertThat(count(t)).isEqualTo(4L);                  // 3 old rows still wait for the next run, the recent one is untouched
        assertThat(store.purge(60, 2, 3)).isEqualTo(3L);
        assertThat(count(t)).isEqualTo(1L);
    }

    @Test
    void theScheduledEntryUsesTheSameBoundedCleanupAndNeverThrows() throws Exception {
        UUID t = shop();
        row(t, "90"); row(t, "2");
        store.purge();   // the @Scheduled method: 60 days, batches of 5000, at most 40 batches
        assertThat(count(t)).isEqualTo(1L);
    }

    @Test
    void aFailingCleanupDoesNotThrowAndDeletesNothing() throws Exception {
        UUID t = shop();
        row(t, "90");
        jdbc.sql("ALTER TABLE chat.reply_traces RENAME TO reply_traces_off").update();
        try {
            assertThat(store.purge(60, 5_000, 40)).isZero();   // the database error is caught inside
        } finally { jdbc.sql("ALTER TABLE chat.reply_traces_off RENAME TO reply_traces").update(); }
        assertThat(count(t)).isEqualTo(1L);   // the old row is still there: nothing was half done
        store.purge(60, 5_000, 40);
        assertThat(count(t)).isZero();
    }

    @Test
    void theTableHasTheIndexesTheCleanupAndTenantQueriesNeedAndCascadesWithItsShop() {
        var indexes = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'chat' AND tablename = 'reply_traces'").query(String.class).list();
        assertThat(indexes.stream().anyMatch(d -> d.contains("(started_at)"))).as("retention scans by started_at").isTrue();
        assertThat(indexes.stream().anyMatch(d -> d.contains("(tenant_id, started_at)"))).as("a shop's traces by time").isTrue();
        assertThat(indexes.stream().anyMatch(d -> d.contains("(trace_id)") && d.contains("UNIQUE"))).as("the primary key").isTrue();
        // the shop foreign key deletes the traces with the shop (confdeltype 'c' = CASCADE); conversation_id is deliberately not a foreign key: a trace outlives nothing it needs
        assertThat(jdbc.sql("SELECT confdeltype::text FROM pg_constraint WHERE conrelid = 'chat.reply_traces'::regclass AND contype = 'f' AND confrelid = 'core.tenants'::regclass").query(String.class).single()).isEqualTo("c");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conrelid = 'chat.reply_traces'::regclass AND contype = 'f'").query(Long.class).single()).isEqualTo(1L);
    }
}

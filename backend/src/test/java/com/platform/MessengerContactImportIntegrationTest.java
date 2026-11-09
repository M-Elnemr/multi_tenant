package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.chat.unofficial.MessengerContactImport;
import com.platform.chat.unofficial.OutOfWindowSender.ContactThread;
import com.platform.chat.unofficial.OutOfWindowSender.ThreadPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Importing the Page's Messenger contacts through the unofficial session. Verified against {@link FakeOutOfWindowSender} only: nothing here touched Facebook. */
class MessengerContactImportIntegrationTest extends IntegrationTestBase {
    static final String CONNECT = "{\"cookies\":\"c_user=1; xs=abcdefghijklmnop\",\"pageId\":\"123456789\"}";
    static final long DAY = 24L * 3600 * 1000;

    @Autowired JdbcClient jdbc;
    @Autowired MessengerContactImport importer;

    @BeforeEach
    void clean() { FakeOutOfWindowSender.reset(); FakeOutOfWindowSender.HANDLE_FB = true; }

    static ContactThread thread(String id, String name, long agoMs) { return new ContactThread(id, name, System.currentTimeMillis() - agoMs, 0); }

    void pages() {
        FakeOutOfWindowSender.THREAD_PAGES.add(ThreadPage.of(List.of(thread("5550001", "Mona Ali", 3 * DAY), thread("5550002", "Hassan", 40 * DAY)), "p2"));
        FakeOutOfWindowSender.THREAD_PAGES.add(ThreadPage.of(List.of(thread("5550003", "Sara with a very long display name that goes past forty characters", 2 * 3600_000L)), null));
    }

    long conversations(UUID t) { return jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND channel = 'FACEBOOK'").param("t", t).query(Long.class).single(); }

    @Test
    void importedContactsAreExistingMessengerCustomers_reachableByTheManualRoute_idempotent_noChannelRow() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String h = t.host(), owner = t.access();
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts/status", null).andExpect(jsonPath("$.status").value("IDLE"));
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEEDS_LOGIN"));
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/INSTAGRAM/import-contacts", null).andExpect(status().isBadRequest());

        // connecting starts the import by itself (the async flag is off in tests, so it has finished when the call returns)
        pages();
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/FACEBOOK/connect", CONNECT).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts/status", null).andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.fetched").value(3))
                .andExpect(jsonPath("$.imported").value(3)).andExpect(jsonPath("$.contacts").value(3));
        assertThat(conversations(id)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM chat.channels WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isZero();   // no placeholder channel row
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t").param("t", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT display_name FROM chat.conversations WHERE tenant_id = :t AND external_id = '5550003'").param("t", id).query(String.class).single()).hasSize(40);
        // even a chat that was active 2 hours ago is outside the window (last activity is capped), so the official route is never used for an imported contact
        assertThat(jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND last_customer_at > now() - interval '24 hours'").param("t", id).query(Long.class).single()).isZero();

        // idempotent: the same contacts again add nothing (a second manual run is incremental and stops after the first page without news)
        pages();
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts/status", null).andExpect(jsonPath("$.imported").value(0)).andExpect(jsonPath("$.fetched").value(2));
        assertThat(conversations(id)).isEqualTo(3);

        // they show up as existing Messenger customers, outside the window, by the manual-style route
        onHost(h, owner, "GET", "/api/v1/store/segments/customers?segments=ALL&platforms=FACEBOOK", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(3)).andExpect(jsonPath("$.manualStyle").value(3)).andExpect(jsonPath("$.data[0].route").value("MANUAL_STYLE")).andExpect(jsonPath("$.data[0].platform").value("FACEBOOK"));
        onHost(h, owner, "GET", "/api/v1/store/segments?platforms=FACEBOOK", null).andExpect(jsonPath("$.byPlatform.FACEBOOK.total").value(3)).andExpect(jsonPath("$.byPlatform.FACEBOOK.reachableOld").value(3))
                .andExpect(jsonPath("$.byPlatform.FACEBOOK.needsConnect").value(false));

        // prospects: someone who is already an imported contact is not a prospect
        jdbc.sql("INSERT INTO commerce.prospects (tenant_id, platform, external_ref, username, display_name, source) VALUES (:t, 'FACEBOOK', '5550001', NULL, 'Mona Ali', 'COMMENTER'), (:t, 'FACEBOOK', '9990009', NULL, 'Stranger', 'COMMENTER')")
                .param("t", id).update();
        onHost(h, owner, "GET", "/api/v1/store/prospects?platform=FACEBOOK", null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].displayName").value("Stranger"));

        // the daily run skips a shop that was imported within the last day
        long runs = jdbc.sql("SELECT count(*) FROM chat.contact_imports WHERE tenant_id = :t").param("t", id).query(Long.class).single();
        importer.runDaily();
        assertThat(jdbc.sql("SELECT count(*) FROM chat.contact_imports WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isEqualTo(runs);
        jdbc.sql("UPDATE chat.contact_imports SET started_at = now() - interval '30 hours' WHERE tenant_id = :t").param("t", id).update();
        importer.runDaily();   // a day later it runs again (and, incremental, finds nothing new)
        assertThat(jdbc.sql("SELECT count(*) FROM chat.contact_imports WHERE tenant_id = :t").param("t", id).query(Long.class).single()).isEqualTo(runs + 1);
    }

    @Test
    void failuresAreReportedWithAStableCode_partialResultsKept() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String h = t.host(), owner = t.access();
        FakeOutOfWindowSender.THREADS_ERROR = "RATE_LIMITED";
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/FACEBOOK/connect", CONNECT).andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts/status", null).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        FakeOutOfWindowSender.THREADS_ERROR = "NEEDS_LOGIN";
        onHost(h, owner, "POST", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts", null).andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/unofficial-meta/FACEBOOK/import-contacts/status", null).andExpect(jsonPath("$.error").value("NEEDS_LOGIN"));
        assertThat(jdbc.sql("SELECT status FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = 'FACEBOOK'").param("t", id).query(String.class).single()).isEqualTo("NEEDS_LOGIN");
        assertThat(conversations(id)).isZero();
    }
}

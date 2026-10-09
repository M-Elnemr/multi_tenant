package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Play Store policy: a shop client can delete their account from the app. */
class ClientAccountDeletionIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void deletingTheAccountRemovesTheIdentityAndTheOldTokenStopsWorking() throws Exception {
        Tenant store = onboard("STORE");
        String sub = "del" + uniq();
        String token = googleClient(store.host(), sub, "Mona Client");
        onHost(store.host(), token, "GET", "/api/v1/shop/me", null).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mona Client"));

        onHost(store.host(), token, "DELETE", "/api/v1/shop/me", null).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE google_sub = :s OR email LIKE :e").param("s", sub).param("e", sub + "%").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE status = 'DELETED' AND name = '' AND email IS NULL AND phone IS NULL").query(Long.class).single()).isGreaterThan(0);
        onHost(store.host(), token, "GET", "/api/v1/shop/me", null).andExpect(status().is4xxClientError());

        // signing in with the same Google identity afterwards starts a brand-new, empty account
        String again = googleClient(store.host(), sub, "Mona Client");
        onHost(store.host(), again, "GET", "/api/v1/shop/me", null).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.client_accounts WHERE google_sub = :s").param("s", sub).query(Long.class).single()).isEqualTo(1);
    }
}

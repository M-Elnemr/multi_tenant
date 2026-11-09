package com.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.billing.OutboundBilling;
import com.platform.billing.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The one place that charges for an outbound message: 0.1 EGP, once, before sending; given back on failure; no credit means no charge and no message. */
class OutboundBillingTest extends IntegrationTestBase {
    @Autowired OutboundBilling billing;
    @Autowired WalletService wallet;
    @Autowired JdbcClient jdbc;

    UUID shop(long balance) throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = :b WHERE tenant_id = :t").param("b", balance).param("t", id).update();
        return id;
    }

    @Test
    void aMessageCostsTenPiastersChargedOnceAndRefundedWhenItFails() throws Exception {
        UUID t = shop(25);
        assertThat(billing.priceMinor()).isEqualTo(10L);
        assertThat(billing.affordable(t, 2)).isTrue();
        assertThat(billing.affordable(t, 3)).isFalse();   // 30 > 25

        assertThat(billing.charge(t, "ref-1", "test message")).isTrue();
        assertThat(wallet.balance(t)).isEqualTo(15L);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND ref = 'ref-1' AND amount_minor = -10 AND kind = 'MESSAGE'").param("t", t).query(Long.class).single()).isEqualTo(1L);   // one debit

        billing.refund(t, "ref-1", "not delivered");
        assertThat(wallet.balance(t)).isEqualTo(25L);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND ref = 'ref-1' AND amount_minor = 10 AND kind = 'REFUND'").param("t", t).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void withoutCreditNothingIsChargedAndTheAnswerIsFalse() throws Exception {
        UUID t = shop(9);
        assertThat(billing.affordable(t, 1)).isFalse();
        assertThat(billing.charge(t, "ref-2", "test")).isFalse();
        assertThat(wallet.balance(t)).isEqualTo(9L);
        assertThat(jdbc.sql("SELECT count(*) FROM billing.wallet_entries WHERE tenant_id = :t AND ref = 'ref-2'").param("t", t).query(Long.class).single()).isZero();
        // the owner is told, but only once in a while
        billing.notifyNoCredit(t, "k", "low", "body");
        billing.notifyNoCredit(t, "k", "low", "body");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type = 'WALLET_LOW'").param("t", t).query(Long.class).single()).isEqualTo(1L);
    }
}

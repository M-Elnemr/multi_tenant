package com.platform.affiliate;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only history of every affiliate action (who, what, on which shop and marketer), for the platform admin and for disputes. */
@Component
public class AffiliateAudit {
    private final JdbcClient jdbc;
    public AffiliateAudit(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void log(String actorType, UUID actorId, UUID tenantId, UUID partnerId, String action, String detail) {
        jdbc.sql("INSERT INTO affiliate.audit (actor_type, actor_id, tenant_id, partner_id, action, detail) VALUES (:t, :a, :tn, :p, :ac, :d)")
                .param("t", actorType).param("a", actorId).param("tn", tenantId).param("p", partnerId).param("ac", action).param("d", detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail).update();
    }
}

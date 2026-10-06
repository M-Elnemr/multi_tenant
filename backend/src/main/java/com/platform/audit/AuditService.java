package com.platform.audit;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Appends an audit entry. Audit rows are insert-only; tenant users have no API to edit them. */
    public void record(UUID actor, UUID tenantId, String action, String entityType, UUID entityId, String metadataJson) {
        jdbc.sql("""
                INSERT INTO audit.audit_logs (actor_user_id, tenant_id, action, entity_type, entity_id, metadata_json)
                VALUES (:actor, :tenant, :action, :etype, :eid, CAST(:meta AS jsonb))
                """)
                .param("actor", actor).param("tenant", tenantId).param("action", action)
                .param("etype", entityType).param("eid", entityId).param("meta", metadataJson)
                .update();
    }
}

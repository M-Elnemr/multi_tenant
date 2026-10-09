package com.platform.commerce;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A shop's memory of a phone number: how many orders arrived, were returned or cancelled, and whether the owner blocked it (COD abuse control). */
@Component
public class PhoneFlags {
    private final JdbcClient jdbc;

    public PhoneFlags(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean isBlocked(UUID tenantId, String phone) {
        return phone != null && jdbc.sql("SELECT blocked FROM commerce.phone_flags WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", phone).query(Boolean.class).optional().orElse(false);
    }

    /** column: delivered_count, returned_count or cancelled_count. */
    public void bump(UUID tenantId, String phone, String column) {
        if (phone == null || phone.isBlank() || !java.util.Set.of("delivered_count", "returned_count", "cancelled_count").contains(column)) return;
        jdbc.sql("INSERT INTO commerce.phone_flags (tenant_id, phone, " + column + ") VALUES (:t, :p, 1) ON CONFLICT (tenant_id, phone) DO UPDATE SET " + column + " = commerce.phone_flags." + column + " + 1, updated_at = now()")
                .param("t", tenantId).param("p", phone).update();
    }

    public Map<String, Object> stats(UUID tenantId, String phone) {
        return jdbc.sql("SELECT delivered_count, returned_count, cancelled_count, blocked, note FROM commerce.phone_flags WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", phone)
                .query().listOfRows().stream().findFirst().map(com.platform.shared.Rows::camel)
                .orElse(Map.of("deliveredCount", 0, "returnedCount", 0, "cancelledCount", 0, "blocked", false, "note", ""));
    }

    public void setBlocked(UUID tenantId, String phone, boolean blocked, String note) {
        jdbc.sql("INSERT INTO commerce.phone_flags (tenant_id, phone, blocked, note) VALUES (:t, :p, :b, :n) ON CONFLICT (tenant_id, phone) DO UPDATE SET blocked = :b, note = :n, updated_at = now()")
                .param("t", tenantId).param("p", phone).param("b", blocked).param("n", note == null ? "" : note.trim()).update();
    }
}

package com.platform.chat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which of the shop's customers a chat belongs to: the signed-in website customer (client account), else the WhatsApp number (or a phone the customer gave in the
 * chat) matched on its last 9 digits. Messenger / Instagram profiles stay unlinked until the customer gives a phone number.
 */
@Component
public class ChatCustomer {
    private final JdbcClient jdbc;

    public ChatCustomer(JdbcClient jdbc) { this.jdbc = jdbc; }

    public UUID resolve(UUID tenantId, String channel, String externalId, UUID clientId, String contactPhone) {
        if (clientId != null) {
            UUID id = jdbc.sql("SELECT id FROM commerce.customers WHERE tenant_id = :t AND user_id = :u LIMIT 1").param("t", tenantId).param("u", clientId).query(UUID.class).optional().orElse(null);
            if (id != null) return id;
        }
        String phone = "WHATSAPP".equals(channel) ? externalId : contactPhone;
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.length() < 9) return null;
        return jdbc.sql("""
                SELECT id FROM commerce.customers WHERE tenant_id = :t
                  AND (right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 9) = :p OR right(regexp_replace(coalesce(whatsapp_number, ''), '\\D', '', 'g'), 9) = :p)
                ORDER BY created_at LIMIT 1
                """).param("t", tenantId).param("p", d.substring(d.length() - 9)).query(UUID.class).optional().orElse(null);
    }

    /** The same match as SQL, for list queries: a customer id expression for the conversation alias {@code c}. */
    static final String SQL = """
            coalesce(
              (SELECT cu.id FROM commerce.customers cu WHERE cu.tenant_id = c.tenant_id AND c.client_id IS NOT NULL AND cu.user_id = c.client_id LIMIT 1),
              (SELECT cu.id FROM commerce.customers cu WHERE cu.tenant_id = c.tenant_id
                 AND length(regexp_replace(coalesce(CASE WHEN c.channel = 'WHATSAPP' THEN c.external_id ELSE c.contact_phone END, ''), '\\D', '', 'g')) >= 9
                 AND (right(regexp_replace(coalesce(cu.phone, ''), '\\D', '', 'g'), 9) = right(regexp_replace(CASE WHEN c.channel = 'WHATSAPP' THEN c.external_id ELSE c.contact_phone END, '\\D', '', 'g'), 9)
                   OR right(regexp_replace(coalesce(cu.whatsapp_number, ''), '\\D', '', 'g'), 9) = right(regexp_replace(CASE WHEN c.channel = 'WHATSAPP' THEN c.external_id ELSE c.contact_phone END, '\\D', '', 'g'), 9))
               ORDER BY cu.created_at LIMIT 1))
            """;
}

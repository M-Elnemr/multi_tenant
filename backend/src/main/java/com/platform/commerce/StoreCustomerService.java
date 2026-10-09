package com.platform.commerce;

import com.platform.shared.PhoneNormalizer;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A store's customers (clients). A customer is created by buying, never by the store: a guest is known by phone number, and a client who
 * signed in with Google is attached to the same record. A store can read its customers but cannot create or edit them.
 */
@Service
public class StoreCustomerService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Contact(String name, String phone, String email, String addressJson) {}

    private final JdbcClient jdbc;

    public StoreCustomerService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The customer record of a signed-in client in this store (created from the account's own details when missing). */
    @Transactional
    public UUID ensureCustomer(UUID tenantId, UUID clientId) {
        var acc = jdbc.sql("SELECT name, phone, email FROM commerce.client_accounts WHERE id = :u").param("u", clientId).query().listOfRows().stream().findFirst().orElseThrow();
        return ensureCustomer(tenantId, clientId, new Contact((String) acc.get("name"), (String) acc.get("phone"), (String) acc.get("email"), null));
    }

    /** Finds or creates the customer: the signed-in client's record first, else the guest with this phone, else a new one. Safe when two orders race. */
    @Transactional
    public UUID ensureCustomer(UUID tenantId, UUID clientId, Contact c) {
        String phone = c.phone() == null || c.phone().isBlank() ? null : PhoneNormalizer.normalize(c.phone());
        String name = c.name() == null || c.name().isBlank() ? "Customer" : c.name().trim();
        for (int attempt = 0; attempt < 5; attempt++) {
            UUID id = null;
            if (clientId != null) id = jdbc.sql("SELECT id FROM commerce.customers WHERE tenant_id = :t AND user_id = :u").param("t", tenantId).param("u", clientId).query(UUID.class).optional().orElse(null);
            if (id == null && phone != null) {
                var byPhone = jdbc.sql("SELECT id, user_id FROM commerce.customers WHERE tenant_id = :t AND phone = :p").param("t", tenantId).param("p", phone).query().listOfRows().stream().findFirst();
                if (byPhone.isPresent()) {
                    id = (UUID) byPhone.get().get("id");
                    if (clientId != null && byPhone.get().get("user_id") == null)
                        jdbc.sql("UPDATE commerce.customers SET user_id = :u WHERE id = :i AND user_id IS NULL").param("u", clientId).param("i", id).update();
                }
            }
            if (id != null) {
                jdbc.sql("""
                        UPDATE commerce.customers c SET name = :n, email = coalesce(:e, c.email), address_json = coalesce(CAST(:a AS jsonb), c.address_json), updated_at = now(),
                          phone = CASE WHEN CAST(:p AS varchar) IS NOT NULL AND NOT EXISTS (SELECT 1 FROM commerce.customers x WHERE x.tenant_id = c.tenant_id AND x.phone = CAST(:p AS varchar) AND x.id <> c.id)
                                       THEN CAST(:p AS varchar) ELSE c.phone END
                        WHERE c.id = :i
                        """).param("n", name).param("e", c.email()).param("a", c.addressJson()).param("p", phone).param("i", id).update();
                return id;
            }
            // A concurrent order for the same phone/client may win the insert; then the next round finds its row.
            var created = jdbc.sql("""
                    INSERT INTO commerce.customers (tenant_id, user_id, customer_number, name, phone, email, address_json)
                    VALUES (:t, :u, :n, :nm, :p, :e, CAST(:a AS jsonb)) ON CONFLICT DO NOTHING RETURNING id
                    """).param("t", tenantId).param("u", clientId).param("n", "C-" + random(6)).param("nm", name).param("p", phone).param("e", c.email()).param("a", c.addressJson())
                    .query(UUID.class).optional();
            if (created.isPresent()) return created.get();
        }
        throw new IllegalStateException("Could not create the customer record");
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}

package com.platform.commerce.domains;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WHOIS/registrant a tenant enters once and reuses across orders - tenant-scoped, never fabricated and never
 * defaulted to Elmanassa's own details. This is the legal registrant that goes to the registrar on every order;
 * {@code DomainOrderService} always resolves it from here, it is never invented inline.
 */
@Service
public class RegistrantContactService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public RegistrantContactService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ContactReq(String name, String org, String email, String phone, String country, String address, String city, String state, String postalCode) {}

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actor, ContactReq r) {
        if (blank(r.name()) || blank(r.email()) || blank(r.phone()) || blank(r.country()) || blank(r.address()))
            throw BusinessException.badRequest("VALIDATION_ERROR", "Name, email, phone, country and address are required");
        if (r.country().trim().length() != 2) throw BusinessException.badRequest("VALIDATION_ERROR", "Country must be a 2-letter code (e.g. EG)");
        UUID id = jdbc.sql("""
                INSERT INTO commerce.registrant_contacts (tenant_id, name, org, email, phone, country, address, city, state, postal_code)
                VALUES (:t, :n, :o, :e, :p, :c, :a, :ci, :st, :pc) RETURNING id
                """).param("t", tenantId).param("n", r.name().trim()).param("o", blank(r.org()) ? null : r.org().trim()).param("e", r.email().trim())
                .param("p", r.phone().trim()).param("c", r.country().trim().toUpperCase()).param("a", r.address().trim())
                .param("ci", blank(r.city()) ? null : r.city().trim()).param("st", blank(r.state()) ? null : r.state().trim())
                .param("pc", blank(r.postalCode()) ? null : r.postalCode().trim())
                .query(UUID.class).single();
        audit.record(actor, tenantId, "REGISTRANT_CONTACT_ADDED", "registrant_contact", id, null);
        return get(tenantId, id);
    }

    public List<Map<String, Object>> list(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, name, org, email, phone, country, address, city, state, postal_code, created_at FROM commerce.registrant_contacts WHERE tenant_id = :t ORDER BY created_at DESC")
                .param("t", tenantId).query().listOfRows());
    }

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT id, name, org, email, phone, country, address, city, state, postal_code, created_at FROM commerce.registrant_contacts WHERE id = :i AND tenant_id = :t")
                .param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().map(Rows::camel)
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Registrant contact not found"));
    }

    /** Raw row (snake_case) for {@code DomainOrderRunner}, which needs it keyed off {@code commerce.domain_orders.registrant_contact_id} directly. */
    Map<String, Object> raw(UUID id) {
        return jdbc.sql("SELECT name, org, email, phone, country, address, city, state, postal_code FROM commerce.registrant_contacts WHERE id = :i")
                .param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ProviderException.invalidContact("Registrant contact is missing"));
    }

    public static DomainProvider.RegistrantInfo toProviderInfo(Map<String, Object> raw) {
        return new DomainProvider.RegistrantInfo((String) raw.get("name"), (String) raw.get("org"), (String) raw.get("email"), (String) raw.get("phone"),
                (String) raw.get("country"), (String) raw.get("address"), (String) raw.get("city"), (String) raw.get("state"), (String) raw.get("postal_code"));
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}

package com.platform.chat;

import com.platform.commerce.StoreContext;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's full profile for the shop team: contact, how they reached the shop, orders and spending, every chat they had on any channel, the team's private notes and tags.
 * Contact details stay read-only (customers own them); the team writes only notes and tags.
 */
@RestController
@RequestMapping("/api/v1/store/customers")
public class CustomerProfileController {
    private final JdbcClient jdbc;
    private final ChatInboxService inbox;

    public CustomerProfileController(JdbcClient jdbc, ChatInboxService inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    public record NotesRequest(String notes) {}
    public record TagRequest(String tag) {}

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer.read')")
    public Map<String, Object> profile(@PathVariable UUID id) {
        UUID t = StoreContext.tenantId();
        var base = inbox.customerSummary(t, id);
        if (base == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Customer not found");
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("orders", Rows.camel(jdbc.sql("SELECT id, order_number, status, total_minor, currency, created_at FROM commerce.orders WHERE customer_id = :c ORDER BY created_at DESC LIMIT 50").param("c", id).query().listOfRows()));
        m.put("conversations", Rows.camel(jdbc.sql("""
                SELECT c.id, c.channel, c.updated_at, c.status, c.unhappy,
                       (SELECT content FROM chat.messages mm WHERE mm.conversation_id = c.id ORDER BY mm.id DESC LIMIT 1) AS last_message
                FROM chat.conversations c WHERE c.tenant_id = :t AND c.channel <> 'TEST' AND (%s) = :cu ORDER BY c.updated_at DESC LIMIT 20
                """.formatted(ChatCustomer.SQL)).param("t", t).param("cu", id).query().listOfRows()));
        return m;
    }

    @PutMapping("/{id}/notes")
    @PreAuthorize("hasAuthority('customer.read')")
    public Map<String, Object> notes(@PathVariable UUID id, @RequestBody NotesRequest r) {
        String n = r.notes() == null ? "" : r.notes().trim();
        if (n.length() > 4000) throw BusinessException.badRequest("VALIDATION_ERROR", "The notes are too long (up to 4000 characters)");
        if (jdbc.sql("UPDATE commerce.customers SET notes = :n WHERE id = :c AND tenant_id = :t").param("n", n.isEmpty() ? null : n).param("c", id).param("t", StoreContext.tenantId()).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Customer not found");
        return Map.of("notes", n);
    }

    @PostMapping("/{id}/tags")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<String> addTag(@PathVariable UUID id, @RequestBody TagRequest r) {
        UUID t = StoreContext.tenantId();
        String tag = r.tag() == null ? "" : r.tag().trim().replaceAll("\\s+", " ");
        if (tag.isEmpty() || tag.length() > 40) throw BusinessException.badRequest("VALIDATION_ERROR", "A tag is 1 to 40 characters");
        own(t, id);
        if (jdbc.sql("SELECT count(*) FROM commerce.customer_tags WHERE customer_id = :c").param("c", id).query(Long.class).single() >= 20) throw BusinessException.conflict("LIMIT_REACHED", "Up to 20 tags per customer");
        jdbc.sql("INSERT INTO commerce.customer_tags (customer_id, tenant_id, tag) VALUES (:c, :t, :g) ON CONFLICT DO NOTHING").param("c", id).param("t", t).param("g", tag).update();
        return tags(id);
    }

    @DeleteMapping("/{id}/tags/{tag}")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<String> removeTag(@PathVariable UUID id, @PathVariable String tag) {
        own(StoreContext.tenantId(), id);
        jdbc.sql("DELETE FROM commerce.customer_tags WHERE customer_id = :c AND tag = :g").param("c", id).param("g", tag).update();
        return tags(id);
    }

    /** Every tag the shop already uses, for suggestions. */
    @GetMapping("/tags")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<String> allTags() {
        return jdbc.sql("SELECT tag FROM commerce.customer_tags WHERE tenant_id = :t GROUP BY tag ORDER BY count(*) DESC LIMIT 50").param("t", StoreContext.tenantId()).query(String.class).list();
    }

    private List<String> tags(UUID id) { return jdbc.sql("SELECT tag FROM commerce.customer_tags WHERE customer_id = :c ORDER BY created_at").param("c", id).query(String.class).list(); }

    private void own(UUID tenantId, UUID id) {
        if (jdbc.sql("SELECT count(*) FROM commerce.customers WHERE id = :c AND tenant_id = :t").param("c", id).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Customer not found");
    }
}

package com.platform.chat;

import com.platform.audit.AuditService;
import com.platform.commerce.StoreContext;
import com.platform.shared.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the shop wants the assistant to do when it answers customers: ORDERS (place product orders, as always), LEADS (collect the customer's name early and their number once they are satisfied,
 * and save them as a customer to message later), BOOKINGS (take appointments for the shop's services). A shop that never chose keeps ORDERS only, so its assistant behaves exactly as before.
 */
@RestController
@RequestMapping("/api/v1/store/ai")
public class ShopGoals {
    public static final List<String> ALL = List.of("ORDERS", "LEADS", "BOOKINGS");
    public static final Set<String> DEFAULT = Set.of("ORDERS");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ShopGoals(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Set<String> of(UUID tenantId) {
        String g = jdbc.sql("SELECT array_to_string(goals, ',') FROM chat.settings WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElse(null);
        if (g == null) return DEFAULT;
        Set<String> out = new LinkedHashSet<>();
        for (String x : g.split(",")) if (ALL.contains(x)) out.add(x);
        return out;
    }

    /** True for a shop that never changed it: its assistant needs no extra instructions or tools. */
    public boolean isDefault(Set<String> goals) { return goals.equals(DEFAULT); }

    public record GoalsReq(List<String> goals) {}

    @GetMapping("/goals")
    @PreAuthorize("hasAuthority('ai.read') or hasAuthority('ai.manage')")
    public Map<String, Object> get() {
        UUID t = StoreContext.tenantId();
        return Map.of("goals", of(t), "available", ALL, "services", jdbc.sql("SELECT count(*) FROM commerce.services WHERE tenant_id = :t AND is_active").param("t", t).query(Long.class).single());
    }

    @PutMapping("/goals")
    @PreAuthorize("hasAuthority('ai.manage')")
    public Map<String, Object> put(@RequestBody GoalsReq r, Authentication a) {
        UUID t = StoreContext.tenantId();
        List<String> in = r.goals() == null ? List.of() : r.goals();
        if (!ALL.containsAll(in)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown goal");
        String csv = String.join(",", ALL.stream().filter(in::contains).toList());
        jdbc.sql("""
                INSERT INTO chat.settings (tenant_id, goals) VALUES (:t, CAST(string_to_array(:g, ',') AS varchar(12)[]))
                ON CONFLICT (tenant_id) DO UPDATE SET goals = CAST(string_to_array(:g, ',') AS varchar(12)[]), updated_at = now()
                """).param("t", t).param("g", csv).update();
        audit.record((UUID) a.getPrincipal(), t, "AI_GOALS_CHANGED", "ai", null, "{\"goals\":\"" + csv + "\"}");
        return get();
    }
}

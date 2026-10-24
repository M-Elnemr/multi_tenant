package com.platform.platform;

import com.platform.audit.AuditService;
import com.platform.files.FileStorage;
import com.platform.shared.BusinessException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes a business and everything it owns: rows, uploaded files, and the patient / customer / staff identities that
 * existed only because of it. Identities that also belong to another business are kept.
 *
 * The tables to clean are discovered from the database catalog (every table with a tenant_id column or a foreign key to
 * core.tenants, plus every table that hangs off one of those by a foreign key) and deleted children-first, so a table
 * added by a future migration is covered without touching this class. {@code TenantPurgeIntegrationTest} proves it.
 */
@Service
public class TenantPurgeService {
    private static final Logger log = LoggerFactory.getLogger(TenantPurgeService.class);

    private final com.platform.chat.EvolutionService evolution;
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final AuditService audit;
    private final com.platform.core.tenant.TenantDirectory directory;
    private final TransactionTemplate tx;

    public TenantPurgeService(JdbcClient jdbc, FileStorage storage, AuditService audit, com.platform.core.tenant.TenantDirectory directory, PlatformTransactionManager tm, com.platform.chat.EvolutionService evolution) {
        this.evolution = evolution;
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.directory = directory;
        this.tx = new TransactionTemplate(tm);
    }

    private record Fk(String child, String childCol, String parent, String parentCol) {}

    public Map<String, Object> purge(UUID actor, UUID tenantId, String confirmSlug) {
        var t = jdbc.sql("SELECT slug, name FROM core.tenants WHERE id = :i").param("i", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
        String slug = (String) t.get("slug");
        if (confirmSlug == null || !confirmSlug.trim().equalsIgnoreCase(slug))
            throw BusinessException.badRequest("CONFIRMATION_MISMATCH", "Type the business address name exactly to confirm");
        evolution.purge(tenantId);   // the shop's own WhatsApp link is removed first: nothing may keep sending for a deleted shop

        Map<String, Object> report = tx.execute(status -> deleteRows(tenantId));
        int keys = 0;
        for (FileStorage.Bucket b : FileStorage.Bucket.values()) {
            try { storage.deletePrefix(b, "tenants/" + tenantId + "/"); keys++; }
            catch (Exception e) { log.error("Could not remove stored files of tenant {} from {}: {}", tenantId, b, e.getMessage()); report.put("storageError_" + b, e.getMessage()); }
        }
        directory.invalidateAll();
        audit.record(actor, null, "TENANT_PURGED", "tenant", tenantId, "{\"slug\":\"" + slug + "\"}");
        report.put("slug", slug);
        report.put("storageBucketsCleaned", keys);
        return report;
    }

    private Map<String, Object> deleteRows(UUID tenantId) {
        Map<String, Object> report = new LinkedHashMap<>();
        // 1. identities that might become orphans (collected before their links are deleted)
        List<UUID> patientAccounts = ids("""
                SELECT user_id FROM medical.patients WHERE tenant_id = :t AND user_id IS NOT NULL
                UNION SELECT guardian_user_id FROM medical.patient_guardians WHERE tenant_id = :t""", tenantId);
        List<UUID> clientAccounts = ids("""
                SELECT user_id FROM commerce.customers WHERE tenant_id = :t AND user_id IS NOT NULL
                UNION SELECT user_id FROM commerce.orders WHERE tenant_id = :t AND user_id IS NOT NULL""", tenantId);
        List<UUID> staff = ids("""
                SELECT user_id FROM core.user_tenant_memberships WHERE tenant_id = :t
                UNION SELECT user_id FROM medical.doctors WHERE tenant_id = :t AND user_id IS NOT NULL""", tenantId);

        // 2. every tenant-owned table, children first
        List<Fk> fks = foreignKeys();
        Set<String> roots = new LinkedHashSet<>(jdbc.sql("""
                SELECT table_schema || '.' || table_name FROM information_schema.columns c
                WHERE column_name = 'tenant_id' AND table_schema NOT IN ('information_schema','pg_catalog')
                  AND (SELECT table_type FROM information_schema.tables x WHERE x.table_schema = c.table_schema AND x.table_name = c.table_name) = 'BASE TABLE'
                """).query(String.class).list());
        for (Fk f : fks) if (f.parent.equals("core.tenants")) roots.add(f.child);
        roots.remove("core.tenants");
        Set<String> scoped = new LinkedHashSet<>(roots);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Fk f : fks) if (scoped.contains(f.parent) && !f.child.equals("core.tenants") && scoped.add(f.child)) grew = true;
        }
        Map<String, List<Fk>> byChild = new HashMap<>();
        for (Fk f : fks) if (scoped.contains(f.child) && (scoped.contains(f.parent))) byChild.computeIfAbsent(f.child, k -> new ArrayList<>()).add(f);

        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String tbl : scoped) visit(tbl, scoped, fks, seen, order);   // a table is listed after everything that points at it
        for (String tbl : order) {
            String sql = "DELETE FROM " + q(tbl) + " WHERE " + predicate(tbl, roots, fks, scoped, new HashSet<>());
            int n = jdbc.sql(sql).param("t", tenantId).update();
            if (n > 0) report.put(tbl, n);
        }

        // 3. identities that belonged only to this business
        report.put("patientAccounts", orphanPatients(patientAccounts));
        report.put("clientAccounts", orphanClients(clientAccounts));
        report.put("staffUsers", orphanStaff(staff));

        jdbc.sql("DELETE FROM core.tenants WHERE id = :t").param("t", tenantId).update();
        return report;
    }

    private void visit(String tbl, Set<String> scoped, List<Fk> fks, Set<String> seen, List<String> order) {
        if (!seen.add(tbl)) return;
        for (Fk f : fks) if (f.parent.equals(tbl) && !f.child.equals(tbl) && scoped.contains(f.child)) visit(f.child, scoped, fks, seen, order);
        order.add(tbl);
    }

    /** SQL condition (columns unqualified) selecting this tenant's rows of the table. */
    private String predicate(String tbl, Set<String> roots, List<Fk> fks, Set<String> scoped, Set<String> path) {
        List<String> parts = new ArrayList<>();
        if (roots.contains(tbl)) {
            if (hasColumn(tbl, "tenant_id")) parts.add("\"tenant_id\" = :t");
            for (Fk f : fks) if (f.child.equals(tbl) && f.parent.equals("core.tenants") && !f.childCol.equals("tenant_id")) parts.add(q1(f.childCol) + " = :t");
        } else {
            if (!path.add(tbl)) throw new IllegalStateException("Foreign key cycle at " + tbl);
            for (Fk f : fks) {
                if (!f.child.equals(tbl) || !scoped.contains(f.parent) || f.parent.equals(tbl)) continue;
                parts.add(q1(f.childCol) + " IN (SELECT " + q1(f.parentCol) + " FROM " + q(f.parent) + " WHERE " + predicate(f.parent, roots, fks, scoped, path) + ")");
            }
            path.remove(tbl);
        }
        if (parts.isEmpty()) throw new IllegalStateException("Cannot scope table " + tbl + " to a tenant");
        return "(" + String.join(" OR ", parts) + ")";
    }

    private final Map<String, Boolean> columnCache = new HashMap<>();

    private boolean hasColumn(String tbl, String col) {
        return columnCache.computeIfAbsent(tbl + "." + col, k -> {
            String[] p = tbl.split("\\.");
            return jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = :s AND table_name = :n AND column_name = :c")
                    .param("s", p[0]).param("n", p[1]).param("c", col).query(Long.class).single() > 0;
        });
    }

    private List<Fk> foreignKeys() {
        List<Fk> out = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT cn.nspname || '.' || c.relname AS child, pn.nspname || '.' || p.relname AS parent,
                       array_length(k.conkey, 1) AS n,
                       (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = k.conrelid AND a.attnum = k.conkey[1]) AS child_col,
                       (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = k.confrelid AND a.attnum = k.confkey[1]) AS parent_col
                FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
                JOIN pg_class p ON p.oid = k.confrelid JOIN pg_namespace pn ON pn.oid = p.relnamespace WHERE k.contype = 'f'
                """).query().listOfRows()) {
            if (((Number) r.get("n")).intValue() != 1) throw new IllegalStateException("Composite foreign key on " + r.get("child") + " is not supported by the purge");
            out.add(new Fk((String) r.get("child"), (String) r.get("child_col"), (String) r.get("parent"), (String) r.get("parent_col")));
        }
        return out;
    }

    private List<UUID> ids(String sql, UUID tenantId) { return jdbc.sql(sql).param("t", tenantId).query(UUID.class).list(); }

    private int orphanPatients(Collection<UUID> candidates) {
        if (candidates.isEmpty()) return 0;
        List<UUID> gone = jdbc.sql("""
                SELECT a.id FROM medical.patient_accounts a WHERE a.id IN (:ids)
                  AND NOT EXISTS (SELECT 1 FROM medical.patients p WHERE p.user_id = a.id)
                  AND NOT EXISTS (SELECT 1 FROM medical.patient_guardians g WHERE g.guardian_user_id = a.id)
                """).param("ids", candidates).query(UUID.class).list();
        if (gone.isEmpty()) return 0;
        dropPrincipalData(gone);
        jdbc.sql("DELETE FROM medical.patient_accounts WHERE id IN (:ids)").param("ids", gone).update();
        return gone.size();
    }

    private int orphanClients(Collection<UUID> candidates) {
        if (candidates.isEmpty()) return 0;
        List<UUID> gone = jdbc.sql("""
                SELECT a.id FROM commerce.client_accounts a WHERE a.id IN (:ids)
                  AND NOT EXISTS (SELECT 1 FROM commerce.customers c WHERE c.user_id = a.id)
                  AND NOT EXISTS (SELECT 1 FROM commerce.orders o WHERE o.user_id = a.id)
                """).param("ids", candidates).query(UUID.class).list();
        if (gone.isEmpty()) return 0;
        dropPrincipalData(gone);
        jdbc.sql("DELETE FROM commerce.addresses WHERE user_id IN (:ids)").param("ids", gone).update();
        jdbc.sql("DELETE FROM commerce.client_accounts WHERE id IN (:ids)").param("ids", gone).update();
        return gone.size();
    }

    private int orphanStaff(Collection<UUID> candidates) {
        if (candidates.isEmpty()) return 0;
        List<UUID> gone = jdbc.sql("""
                SELECT u.id FROM core.users u WHERE u.id IN (:ids)
                  AND NOT EXISTS (SELECT 1 FROM core.user_tenant_memberships m WHERE m.user_id = u.id)
                  AND NOT EXISTS (SELECT 1 FROM core.user_platform_roles r WHERE r.user_id = u.id)
                  AND NOT EXISTS (SELECT 1 FROM medical.doctors d WHERE d.user_id = u.id)
                """).param("ids", candidates).query(UUID.class).list();
        if (gone.isEmpty()) return 0;
        dropPrincipalData(gone);
        jdbc.sql("DELETE FROM core.activation_pins WHERE user_id IN (:ids)").param("ids", gone).update();
        jdbc.sql("DELETE FROM core.users WHERE id IN (:ids)").param("ids", gone).update();
        return gone.size();
    }

    /** Sessions, tokens, devices and notifications of a person who no longer exists (they link by value, not by foreign key). */
    private void dropPrincipalData(Collection<UUID> ids) {
        jdbc.sql("DELETE FROM core.user_sessions WHERE user_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM core.sso_tickets WHERE user_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM notifications.device_tokens WHERE principal_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM notifications.notifications WHERE user_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM notifications.outbox WHERE user_id IN (:ids)").param("ids", ids).update();
    }

    private static String q(String qualified) {
        String[] p = qualified.split("\\.");
        return q1(p[0]) + "." + q1(p[1]);
    }

    private static String q1(String ident) { return "\"" + ident.replace("\"", "\"\"") + "\""; }
}

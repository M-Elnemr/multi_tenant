package com.platform.shared;

/**
 * Which shops may still do things. A shop that is suspended, cancelled or archived does nothing on its own: no scheduled message, ad change, sync or webhook,
 * and no automatic answer. (A shop in its payment grace period, PAST_DUE, keeps running.) Every background job adds {@link #running} to the query that picks its work.
 */
public final class TenantState {
    private TenantState() {}

    public static final String STOPPED = "('SUSPENDED','CANCELLED','ARCHIVED')";

    /** SQL condition: the shop whose id is in {@code tenantIdColumn} is allowed to run. */
    public static String running(String tenantIdColumn) {
        return "EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = " + tenantIdColumn + " AND tn.status NOT IN " + STOPPED + ")";
    }
}
